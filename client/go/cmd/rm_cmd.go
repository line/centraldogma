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

	"github.com/line/centraldogma/client/go/client"
	"github.com/line/centraldogma/client/go/json"
	"github.com/line/centraldogma/client/go/util/commit"
	"github.com/urfave/cli"
)

type rmFileCommand struct {
	repo repositoryRequestInfo
}

func (rf *rmFileCommand) execute(c *cli.Context) error {
	commitMessage, err := commit.GetMessage(rf.repo.repositoryPath, commit.Remove)
	if err != nil {
		return err
	}
	type data struct {
		CM *json.CommitMessage `json:"commitMessage"`
	}
	buf := new(bytes.Buffer)
	json.NewEncoder(buf).Encode(data{CM: commitMessage})
	repo := rf.repo

	u, _ := url.Parse(
		repo.remote.String() + "projects/" + repo.projectName + "/repositories/" + repo.repositoryName +
			"/delete/revisions/" + repo.revision + repo.repositoryPath)
	req := &http.Request{Method: http.MethodPost, URL: u, Body: ioutil.NopCloser(buf)}
	req.Header = http.Header{"Content-Type": {"application/json"}}
	res, err := client.New().Do(req, c)
	if err != nil {
		return err
	}

	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("cannot delete the file: /%s/%s%s revision: %q (status: %s)",
			repo.projectName, repo.repositoryName, repo.repositoryPath, repo.revision, res.Status)
	}
	fmt.Printf("Deleted: /%s/%s%s\n", repo.projectName, repo.repositoryName, repo.repositoryPath)
	return nil
}

func newRMCommand(c *cli.Context, revision string) (Command, error) {
	if len(c.Args()) != 1 {
		return nil, newCommandLineError(c)
	}
	repo, err := newRepositoryRequestInfo(c, revision, true)
	if err != nil {
		return nil, err
	}

	return &rmFileCommand{repo: repo}, nil
}
