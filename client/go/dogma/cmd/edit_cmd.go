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
	"encoding/json"
	"fmt"
	"io/ioutil"
	"net/http"
	"os"
	"path"

	"github.com/line/centraldogma/client/go/dogma"
	"github.com/urfave/cli"
)

// An editFileCommand modifies the file of the specified path with the revision.
type editFileCommand struct {
	repo repositoryRequestInfo
}

func (ef *editFileCommand) execute(c *cli.Context) error {
	repo := ef.repo
	remoteEntry, err := getRemoteFileEntry(
		c, repo.remoteURL, repo.projName, repo.repoName, repo.path, repo.revision, nil)
	if err != nil {
		return err
	}
	change, err := editRemoteFileContent(remoteEntry)
	if err != nil {
		return err
	}

	commitMessage, err := getCommitMessage(c, change.Path, edition)
	if err != nil {
		return err
	}

	client, err := newDogmaClient(c, repo.remoteURL)
	if err != nil {
		return err
	}

	_, res, err := client.Push(context.Background(),
		repo.projName, repo.repoName, repo.revision, commitMessage, []*dogma.Change{change})
	if err != nil {
		return err
	}
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("failed to edit the file: /%s/%s%s revision: %q (status: %s)",
			repo.projName, repo.repoName, repo.path, repo.revision, res.Status)
	}

	fmt.Printf("Edited: /%s/%s%s\n", repo.projName, repo.repoName, repo.path)
	return nil
}

func editRemoteFileContent(remote *dogma.Entry) (*dogma.Change, error) {
	tempFilePath, err := putIntoTempFile(remote)
	if err != nil {
		return nil, err
	}
	defer os.Remove(tempFilePath)

	cmd := cmdToOpenEditor(tempFilePath)
	if err = cmd.Start(); err != nil {
		return nil, err
	}
	err = cmd.Wait()
	if err != nil {
		return nil, fmt.Errorf("failed to edit the file: %s", path.Base(remote.Path))
	}

	fd, err := os.Open(tempFilePath)
	if err != nil {
		return nil, err
	}
	defer fd.Close()

	buf, err := ioutil.ReadAll(fd)
	if err != nil {
		return nil, fmt.Errorf("failed to edit the file: %s", path.Base(remote.Path))
	}

	change := &dogma.Change{Path: remote.Path}
	if remote.Type == dogma.JSON {
		change.Type = dogma.UpsertJSON
		var v interface{}
		if err := json.Unmarshal(buf, &v); err != nil {
			return nil, err
		}
		change.Content = v
	} else if remote.Type == dogma.Text {
		change.Type = dogma.UpsertText
		change.Content = string(buf)
	}

	return change, nil
}

// newEditCommand creates the editCommand.
func newEditCommand(c *cli.Context) (Command, error) {
	repo, err := newRepositoryRequestInfo(c)
	if err != nil {
		return nil, err
	}
	return &editFileCommand{repo: repo}, nil
}
