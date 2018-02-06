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
	"fmt"
	"net/http"
	"strings"
)

type contentService service

// Entry represents an entry in the repository.
type Entry struct {
	Path       string      `json:"path"`
	Type       string      `json:"type"` // can be JSON, TEXT or DIRECTORY
	Content    interface{} `json:"content,omitempty"`
	Revision   string      `json:"revision,omitempty"`
	URL        string      `json:"url,omitempty"`
	ModifiedAt string      `json:"modifiedAt,omitempty"`
}

// Commit represents a commit in the repository.
type Commit struct {
	Revision      int            `json:"revision"`
	Author        string         `json:"author"`
	PushedAt      string         `json:"pushedAt,omitempty"`
	CommitMessage *CommitMessage `json:"commitMessage,omitempty"`
	Entries       []*Entry       `json:"entries,omitempty"`
}

// CommitMessages represents a commit message in the repository.
type CommitMessage struct {
	Summary string `json:"summary"`
	Detail  string `json:"detail,omitempty"`
	Markup  string `json:"markup,omitempty"`
}

// Change represents a change to commit in the repository.
type Change struct {
	Path    string      `json:"path"`

	// can be UPSERT_JSON, UPSERT_TEXT, REMOVE, RENAME, APPLY_JSON_PATCH or APPLY_TEXT_PATCH
	Type    string      `json:"type"`
	Content interface{} `json:"content,omitempty"`
}

func (con *contentService) listFiles(ctx context.Context,
	projectName, repoName, revision, pathPattern string) ([]*Entry, *http.Response, error) {
	u := fmt.Sprintf("%vprojects/%v/repos/%v/tree%v", DefaultPathPrefix, projectName, repoName, pathPattern)

	if len(revision) != 0 {
		u += "?revision=" + revision
	}

	req, err := con.client.newRequest("GET", u, nil)
	if err != nil {
		return nil, nil, err
	}

	var entries []*Entry
	res, err := con.client.do(ctx, req, &entries)
	if err != nil {
		return nil, res, err
	}
	return entries, res, nil
}

func (con *contentService) getFile(ctx context.Context,
	projectName, repoName, revision, path string, jsonPaths []string) (*Entry, *http.Response, error) {
	if !strings.HasPrefix(path, "/") {
		path = "/" + path
	}

	u := fmt.Sprintf("%vprojects/%v/repos/%v/contents%v", DefaultPathPrefix, projectName, repoName, path)
	query, err := getFileQuery(revision, path, jsonPaths)
	if err != nil {
		return nil, nil, err
	}
	u += query

	req, err := con.client.newRequest("GET", u, nil)
	if err != nil {
		return nil, nil, err
	}

	entry := new(Entry)
	res, err := con.client.do(ctx, req, entry)
	if err != nil {
		return nil, res, err
	}

	return entry, res, nil
}

func getFileQuery(revision, path string, jsonPaths []string) (query string, err error) {
	if len(jsonPaths) != 0 {
		if query, err = getJSONPaths(path, jsonPaths); err != nil {
			return "", err
		}

		if len(revision) != 0 {
			// have both of the jsonpath and the revision
			query += "&revision=" + revision
		}
	} else if len(revision) != 0 {
		// have the revision only
		query += "?revision=" + revision
	}
	return query, nil
}

func getJSONPaths(path string, jsonPaths []string) (query string, err error) {
	if !strings.HasSuffix(strings.ToLower(path), "json") {
		return "", fmt.Errorf("the extension of the file should be .json (path: %v)", path)
	}
	for i, jsonPath := range jsonPaths {
		if i == 0 {
			query = "?"
		} else {
			query += "&"
		}
		query += fmt.Sprintf("jsonpath=%v", jsonPath)
	}
	// the query starts with '?'
	return query, nil
}

func (con *contentService) getFiles(ctx context.Context,
	projectName, repoName, revision, pathPattern string) ([]*Entry, *http.Response, error) {
	u := fmt.Sprintf("%vprojects/%v/repos/%v/contents%v", DefaultPathPrefix, projectName, repoName, pathPattern)

	if len(revision) != 0 {
		u += "?revision=" + revision
	}

	req, err := con.client.newRequest("GET", u, nil)
	if err != nil {
		return nil, nil, err
	}

	var entries []*Entry
	res, err := con.client.do(ctx, req, &entries)
	if err != nil {
		return nil, res, err
	}
	return entries, res, nil
}

func (con *contentService) getHistory(ctx context.Context,
	projectName, repoName, from, to, pathPattern string) ([]*Commit, *http.Response, error) {
	u := fmt.Sprintf("%vprojects/%v/repos/%v/commits/%v", DefaultPathPrefix, projectName, repoName, from)

	query := ""
	if len(pathPattern) != 0 {
		query = fmt.Sprintf("?path=%v", pathPattern)
		if len(to) != 0 {
			query += fmt.Sprintf("&to=%v", to)
		}
	} else if len(to) != 0 {
		query = fmt.Sprintf("?to=%v", to)
	}
	u += query

	req, err := con.client.newRequest("GET", u, nil)
	if err != nil {
		return nil, nil, err
	}

	var commits []*Commit
	res, err := con.client.do(ctx, req, &commits)
	if err != nil {
		return nil, res, err
	}
	return commits, res, nil
}

func (con *contentService) getDiff(ctx context.Context,
	projectName, repoName, from, to, path string, jsonPaths []string) (*Change, *http.Response, error) {
	if len(path) == 0 {
		return nil, nil, fmt.Errorf("the path should not be empty")
	}
	if !strings.HasPrefix(path, "/") {
		path = "/" + path
	}

	u := fmt.Sprintf("%vprojects/%v/repos/%v/compare", DefaultPathPrefix, projectName, repoName)
	query, err := getDiffQuery(from, to, path, jsonPaths)
	if err != nil {
		return nil, nil, err
	}
	u += query

	req, err := con.client.newRequest("GET", u, nil)
	if err != nil {
		return nil, nil, err
	}

	change := new(Change)
	res, err := con.client.do(ctx, req, change)
	if err != nil {
		return nil, res, err
	}

	return change, res, nil
}

func getDiffQuery(from, to, path string, jsonPaths []string) (query string, err error) {
	if len(jsonPaths) != 0 {
		if query, err = getJSONPaths(path, jsonPaths); err != nil {
			return "", err
		}
	}

	if !strings.HasPrefix(query, "?") {
		query = fmt.Sprintf("?path=%v", path)
	} else {
		query += fmt.Sprintf("&path=%v", path)
	}

	if len(from) != 0 {
		query += fmt.Sprintf("&from=%v", from)
	}

	if len(to) != 0 {
		query += fmt.Sprintf("&to=%v", to)
	}

	return query, nil
}

func (con *contentService) getDiffs(ctx context.Context,
	projectName, repoName, from, to, pathPattern string) ([]*Change, *http.Response, error) {
	if len(pathPattern) == 0 {
		pathPattern = "/**"
	}

	u := fmt.Sprintf("%vprojects/%v/repos/%v/compare", DefaultPathPrefix, projectName, repoName)
	query, err := getDiffQuery(from, to, pathPattern, nil)
	if err != nil {
		return nil, nil, err
	}
	u += query

	req, err := con.client.newRequest("GET", u, nil)
	if err != nil {
		return nil, nil, err
	}

	var changes []*Change
	res, err := con.client.do(ctx, req, &changes)
	if err != nil {
		return nil, res, err
	}
	return changes, res, nil
}

type Push struct {
	CommitMessage *CommitMessage `json:"commitMessage"`
	Changes       []Change       `json:"changes"`
}

func (con *contentService) push(ctx context.Context, projectName, repoName, baseRevision string,
	commitMessage CommitMessage, changes []Change) (*Commit, *http.Response, error) {
	if len(commitMessage.Summary) == 0 {
		return nil, nil, fmt.Errorf(
			"summary of commitMessage cannot be empty. commitMessage: %+v", commitMessage)
	}

	if len(changes) == 0 {
		return nil, nil, fmt.Errorf("no changes to commit")
	}

	u := fmt.Sprintf("%vprojects/%v/repos/%v/contents", DefaultPathPrefix, projectName, repoName)

	if len(baseRevision) != 0 {
		u += fmt.Sprintf("?revision=%v", baseRevision)
	}

	body := Push{CommitMessage: &commitMessage, Changes: changes}

	req, err := con.client.newRequest("POST", u, body)
	if err != nil {
		return nil, nil, err
	}

	commit := new(Commit)
	res, err := con.client.do(ctx, req, commit)
	if err != nil {
		return nil, res, err
	}
	return commit, res, nil
}
