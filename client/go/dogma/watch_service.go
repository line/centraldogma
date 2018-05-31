// Copyright 2018 LINE Corporation
//
// LINE Corporation licenses this file to you under the Apache License,
// version 2.0 (the "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at:
//
//   https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations
// under the License.

package dogma

import (
	"context"
	"errors"
	"fmt"
	"math/rand"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type watchService service

// WatchResult represents a result from watch operation.
type WatchResult struct {
	Commit *Commit
	Res    *http.Response
	Err    error
}

func (ws *watchService) watchFile(ctx context.Context, projectName, repoName, lastKnownRevision string,
	query *Query, timeout time.Duration) <-chan *WatchResult {
	watchResult := make(chan *WatchResult)
	if query == nil {
		watchResult <- &WatchResult{Err: errors.New("query should not be nil")}
		return watchResult
	}

	u := fmt.Sprintf("%vprojects/%v/repos/%v/contents%v", defaultPathPrefix, projectName, repoName, query.Path)
	v := &url.Values{}
	if query != nil && query.Type == JSONPath {
		if err := setJSONPaths(v, query.Path, query.Expressions); err != nil {
			watchResult <- &WatchResult{Err: err}
			return watchResult
		}
	}
	u += encodeValues(v)
	ws.watchRequest(ctx, watchResult, u, lastKnownRevision, timeout)
	return watchResult
}

func (ws *watchService) watchRepo(ctx context.Context,
	projectName, repoName, lastKnownRevision, pathPattern string, timeout time.Duration) <-chan *WatchResult {
	watchResult := make(chan *WatchResult)
	if len(pathPattern) != 0 && !strings.HasPrefix(pathPattern, "/") {
		// Normalize the pathPattern when it does not start with "/" so that the pathPattern fits into the url.
		pathPattern = "/**/" + pathPattern
	}

	u := fmt.Sprintf("%vprojects/%v/repos/%v/contents%v", defaultPathPrefix, projectName, repoName, pathPattern)
	ws.watchRequest(ctx, watchResult, u, lastKnownRevision, timeout)
	return watchResult
}

func (ws *watchService) watchRequest(ctx context.Context, watchResult chan<- *WatchResult,
	u, lastKnownRevision string, timeout time.Duration) {
	req, err := ws.client.newRequest(http.MethodGet, u, nil)
	if err != nil {
		watchResult <- &WatchResult{Err: err}
		return
	}
	if len(lastKnownRevision) != 0 {
		req.Header.Set("if-none-match", lastKnownRevision)
	} else {
		req.Header.Set("if-none-match", "-1")
	}
	if timeout != 0 {
		req.Header.Set("prefer", fmt.Sprintf("wait=%v", timeout.Seconds()))
	}

	go func() {
		commit := new(Commit)
		res, err := ws.client.do(ctx, req, commit)
		if err != nil {
			watchResult <- &WatchResult{Commit: nil, Res: res, Err: err}
		} else {
			watchResult <- &WatchResult{Commit: commit, Res: res, Err: nil}
		}
	}()
}

const watchTimeout = 1 * time.Minute

// These constants represent the state of a watcher.
const (
	initial int32 = iota
	started
	stopped
)

// Watcher watches the changes of a repository or a file.
type Watcher struct {
	state               int32
	initialValueCh      chan *Latest // channel whose buffer is 1.
	isInitialValueChSet int32        // 0 is false, 1 is true
	watchCTX            context.Context
	watchCancelFunc     func()
	latest              atomic.Value
	updateListeners     []func(revision int, value interface{})
	listenersMutex      *sync.Mutex

	doWatchFunc          func(lastKnownRevision int) <-chan *WatchResult
	convertingResultFunc func(result *WatchResult) *Latest

	projectName string
	repoName    string
	pathPattern string
}

// Latest represents a holder of the latest known value and its Revision retrieved by Watcher.
type Latest struct {
	Revision int
	Value    interface{}
	Err      error
}

func newWatcher(projectName, repoName, pathPattern string) *Watcher {
	rand.Seed(time.Now().UTC().UnixNano())
	watchCTX, watchCancelFunc := context.WithCancel(context.Background())
	return &Watcher{state: initial, initialValueCh: make(chan *Latest, 1),
		watchCTX: watchCTX, watchCancelFunc: watchCancelFunc,
		listenersMutex: &sync.Mutex{},
		projectName:    projectName,
		repoName:       repoName, pathPattern: pathPattern}
}

// AwaitInitialValue awaits for the initial value to be available.
func (w *Watcher) AwaitInitialValue() Latest {
	latest := <-w.initialValueCh
	// Put it back to the channel so that this can return the value multiple times.
	w.initialValueCh <- latest
	return *latest
}

// AwaitInitialValue awaits for the initial value to be available during the specified timeout.
func (w *Watcher) AwaitInitialValueWith(timeout time.Duration) Latest {
	select {
	case latest := <-w.initialValueCh:
		// Put it back to the channel so that this can return the value multiple times.
		w.initialValueCh <- latest
		return *latest
	case <-time.After(timeout):
		return Latest{Err: fmt.Errorf("failed to get the initial value. timeout: %v", timeout)}
	}
}

// Latest returns the latest Revision and value of WatchFile() or WatchRepository() result.
func (w *Watcher) Latest() Latest {
	latest := w.latest.Load()
	if latest != nil {
		if l, ok := latest.(Latest); ok {
			return l
		}
	}
	return Latest{Err: errors.New("latest is not set yet")}
}

// LatestValue returns the latest value of watchFile() or WatchRepository() result.
func (w *Watcher) LatestValue() (interface{}, error) {
	latest := w.Latest()
	if latest.Err != nil {
		return nil, latest.Err
	}
	return latest.Value, nil
}

// LatestValue returns the latest value of watchFile() or WatchRepository() result. If it's not available, this
// returns the defaultValue.
func (w *Watcher) LatestValueOr(defaultValue interface{}) interface{} {
	latest := w.Latest()
	if latest.Err != nil {
		return defaultValue
	}
	return latest.Value
}

// Close stops watching the file specified in the Query or the pathPattern in the repository.
func (w *Watcher) Close() {
	atomic.StoreInt32(&w.state, stopped)
	latest := &Latest{Err: errors.New("watcher is closed")}
	if atomic.CompareAndSwapInt32(&w.isInitialValueChSet, 0, 1) {
		// The initial latest was not set before. So write the value to initialValueCh as well.
		w.initialValueCh <- latest
	}
	w.watchCancelFunc() // After the first call, subsequent calls to a CancelFunc do nothing.
}

// Watch registers a func that will be invoked when the value of the watched entry becomes available or changes.
func (w *Watcher) Watch(listener func(revision int, value interface{})) error {
	if listener == nil {
		return errors.New("listener is nil")
	}
	if w.isStopped() {
		return errors.New("watcher is closed")
	}

	w.listenersMutex.Lock()
	defer w.listenersMutex.Unlock()
	w.updateListeners = append(w.updateListeners, listener)

	if latest := w.Latest(); latest.Err == nil {
		go func() {
			// Perform initial notification so that the listener always gets the initial value.
			listener(latest.Revision, latest.Value)
		}()
	}

	return nil
}

func (ws *watchService) fileWatcher(projectName, repoName string, query *Query) (*Watcher, error) {
	if query == nil {
		return nil, errors.New("query should not be nil")
	}

	w := newWatcher(projectName, repoName, query.Path)
	w.doWatchFunc = func(lastKnownRevision int) <-chan *WatchResult {
		return ws.watchFile(w.watchCTX, projectName, repoName, strconv.Itoa(lastKnownRevision),
			query, watchTimeout)
	}
	w.convertingResultFunc = func(result *WatchResult) *Latest {
		value := result.Commit.Entries[0].Content
		return &Latest{Revision: result.Commit.Revision, Value: value}
	}
	return w, nil
}

func (ws *watchService) repoWatcher(projectName, repoName, pathPattern string) (*Watcher, error) {
	w := newWatcher(projectName, repoName, pathPattern)
	w.doWatchFunc = func(lastKnownRevision int) <-chan *WatchResult {
		return ws.watchRepo(w.watchCTX, projectName, repoName, strconv.Itoa(lastKnownRevision),
			pathPattern, watchTimeout)
	}
	w.convertingResultFunc = func(result *WatchResult) *Latest {
		revision := result.Commit.Revision
		return &Latest{Revision: revision, Value: revision}
	}
	return w, nil
}

func (w *Watcher) start() {
	if atomic.CompareAndSwapInt32(&w.state, initial, started) {
		w.scheduleWatch(0)
	}
}

func (w *Watcher) scheduleWatch(numAttemptsSoFar int) {
	if w.isStopped() {
		return
	}

	var delay time.Duration
	if numAttemptsSoFar == 0 {
		latest := w.Latest()
		if latest.Err != nil {
			delay = delayOnSuccess
		} else {
			delay = 0
		}
	} else {
		delay = nextDelay(numAttemptsSoFar)
	}

	go func() {
		select {
		case <-w.watchCTX.Done():
		case <-time.NewTimer(delay).C:
			w.doWatch(numAttemptsSoFar)
		}
	}()
}

func (w *Watcher) isStopped() bool {
	state := atomic.LoadInt32(&w.state)
	return state == stopped
}

func (w *Watcher) doWatch(numAttemptsSoFar int) {
	if w.isStopped() {
		return
	}

	var lastKnownRevision int
	curLatest := w.Latest()
	if curLatest.Revision == 0 {
		lastKnownRevision = 1 // Init revision
	} else {
		lastKnownRevision = curLatest.Revision
	}

	select {
	case <-w.watchCTX.Done():
	case watchResult := <-w.doWatchFunc(lastKnownRevision):
		if watchResult.Err != nil {
			if watchResult.Err == context.Canceled {
				// Cancelled by close()
				return
			}

			log.Debug(watchResult.Err)
			w.scheduleWatch(numAttemptsSoFar + 1)
			return
		}

		newLatest := w.convertingResultFunc(watchResult)
		if w.isInitialValueChSet == 0 && atomic.CompareAndSwapInt32(&w.isInitialValueChSet, 0, 1) {
			// The initial latest is set for the first time. So write the value to initialValueCh as well.
			w.initialValueCh <- newLatest
		}
		w.latest.Store(*newLatest)
		log.Debugf("Watcher noticed updated file: %s/%s%s, rev=%v",
			w.projectName, w.repoName, w.pathPattern, newLatest.Revision)
		w.notifyListeners()
		w.scheduleWatch(0)
	}
}

func (w *Watcher) notifyListeners() {
	if w.isStopped() {
		// Do not notify after stopped.
		return
	}

	latest := w.Latest()
	w.listenersMutex.Lock()
	listenersSnapshot := make([]func(revision int, value interface{}), len(w.updateListeners))
	copy(listenersSnapshot, w.updateListeners)
	w.listenersMutex.Unlock()

	go func() {
		for _, listener := range listenersSnapshot {
			listener(latest.Revision, latest.Value)
		}
	}()
}
