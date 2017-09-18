// Copyright 2017 LINE Corporation
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
	"bytes"
	"fmt"
	"io/ioutil"
	"net/http"
	"net/url"
	"os"
	"path"
	"strings"

	"github.com/line/centraldogma/client/go/client"
	"github.com/line/centraldogma/client/go/json"
	"github.com/line/centraldogma/client/go/util/commit"
	"github.com/line/centraldogma/client/go/util/file"
	"github.com/urfave/cli"
)

// An editCommand modifies the file of the specified path with the revision.
type editFileCommand struct {
	repo repositoryRequestInfo
}

func (ef *editFileCommand) execute(c *cli.Context) error {
	repo := ef.repo
	remoteFileEntry, err := getRemoteFileEntry(
		c, repo.remote, repo.projectName, repo.repositoryName, repo.repositoryPath, repo.revision, "")
	if err != nil {
		return err
	}
	entry, err := editRemoteFileContent(remoteFileEntry.File)
	if err != nil {
		return err
	}
	entry.Type = remoteFileEntry.File.Type
	entry.Path = remoteFileEntry.File.Path

	commitMessage, err := commit.GetMessage(entry.Path, commit.Edit)
	if err != nil {
		return err
	}

	entryWithCommit := &json.EntryWithCommit{File: entry, CM: commitMessage}
	buf := new(bytes.Buffer)
	json.NewEncoder(buf).Encode(entryWithCommit)

	u, _ := url.Parse(
		repo.remote.String() + "projects/" + repo.projectName + "/repositories/" + repo.repositoryName +
			"/files/revisions/" + repo.revision)
	req := &http.Request{Method: http.MethodPost, URL: u, Body: ioutil.NopCloser(buf)}
	req.Header = http.Header{"Content-Type": {"application/json"}}
	res, err := client.New().Do(req, c)
	if err != nil {
		return err
	}
	defer res.Body.Close()

	if res.StatusCode != http.StatusCreated {
		return fmt.Errorf("cannot edit the file: /%s/%s%s revision: %q (status: %s)",
			repo.projectName, repo.repositoryName, repo.repositoryPath, repo.revision, res.Status)
	}
	fmt.Printf("Modified: /%s/%s%s\n", repo.projectName, repo.repositoryName, repo.repositoryPath)
	return nil
}

func editRemoteFileContent(remote *json.Entry) (*json.Entry, error) {
	tempFilePath, err := file.PutIntoTempFile(remote)
	if err != nil {
		return nil, err
	}
	defer os.Remove(tempFilePath)

	cmd := file.CmdToOpenEditor(tempFilePath)
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

	if strings.EqualFold(remote.Type, "JSON") && !json.Valid(buf) {
		return nil, fmt.Errorf("failed to edit the file: %s (not a valid JSON format)", path.Base(remote.Path))
	}

	return &json.Entry{Content: string(buf)}, nil
}

// newEditCommand creates the editCommand. If the revision is not specified, head will be set by default.
func newEditCommand(c *cli.Context, revision string) (Command, error) {
	if len(c.Args()) != 1 {
		return nil, newCommandLineError(c)
	}
	repo, err := newRepositoryRequestInfo(c, revision, true)
	if err != nil {
		return nil, err
	}
	return &editFileCommand{repo: repo}, nil
}
