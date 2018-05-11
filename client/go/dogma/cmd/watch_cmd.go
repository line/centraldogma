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

package cmd

import (
	"context"
	"fmt"
	"os"
	"os/signal"
	"strings"

	"github.com/urfave/cli"
)

type watchCommand struct {
	repo      repositoryRequestInfo
	jsonPaths []string
	streaming bool
}

func (wc *watchCommand) execute(c *cli.Context) error {
	repo := wc.repo
	client, err := newDogmaClient(c, repo.remoteURL)
	if err != nil {
		return err
	}

	normalizedRevision, _, err := client.NormalizeRevision(
		context.Background(), repo.projName, repo.repoName, repo.revision)
	if err != nil {
		return err
	}

	query := createQuery(repo.path, wc.jsonPaths)
	fw, err := client.FileWatcher(repo.projName, repo.repoName, query)
	if err != nil {
		return err
	}

	cleanupDone := make(chan bool)
	listener := func(revision int, value interface{}) {
		if revision > normalizedRevision {
			fmt.Printf("Watcher noticed updated file: %s/%s%s, rev=%v\n",
				repo.projName, repo.repoName, repo.path, revision)
			content := ""
			if strings.HasSuffix(strings.ToLower(repo.path), ".json") {
				b, err := marshalIndent(value)
				if err != nil {
					fmt.Printf("Failed to print the content: %v", value)
					return
				}
				content = string(b)
			} else {
				if str, ok := value.(string); ok {
					content = str
				} else {
					fmt.Printf("Failed to print the content: %v", value)
					return
				}
			}
			fmt.Printf("Content:\n%s\n", content)

			if !wc.streaming {
				fw.Close()
				cleanupDone <- true
			}
		}
	}

	fw.Watch(listener)

	signalChan := make(chan os.Signal, 1)
	signal.Notify(signalChan, os.Interrupt)
	go func() {
		for _ = range signalChan {
			fmt.Println("\nReceived an interrupt, stopping watcher...")
			fw.Close()
			cleanupDone <- true
		}
	}()
	<-cleanupDone
	return nil
}

// newWatchCommand creates the watchCommand.
func newWatchCommand(c *cli.Context) (Command, error) {
	repo, err := newRepositoryRequestInfo(c)
	if err != nil {
		return nil, err
	}

	return &watchCommand{repo: repo, jsonPaths: c.StringSlice("jsonpath"), streaming: c.Bool("streaming")}, nil
}
