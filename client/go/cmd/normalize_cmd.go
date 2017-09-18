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
	"fmt"
	"net/http"
	"net/url"

	"github.com/line/centraldogma/client/go/client"
	"github.com/line/centraldogma/client/go/json"
	"github.com/urfave/cli"
)

type normalizeRevisionCommand struct {
	repo repositoryRequestInfo
}

func (nr *normalizeRevisionCommand) execute(c *cli.Context) error {
	repo := nr.repo
	revision, err := getNormalizedRevision(c, repo)
	if err != nil {
		return err
	}
	fmt.Printf("normalized revision: %q\n", revision.RevisionNumber)
	return nil
}

func getNormalizedRevision(c *cli.Context, repo repositoryRequestInfo) (*json.Revision, error) {
	u, _ := url.Parse(
		repo.remote.String() + "projects/" + repo.projectName + "/repositories/" + repo.repositoryName +
			"/revision/" + repo.revision)
	req := &http.Request{Method: http.MethodGet, URL: u}
	res, err := client.New().Do(req, c)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("cannot normalize /%s/%s revision: %q (status: %s)",
			repo.projectName, repo.repositoryName, repo.revision, res.Status)
	}
	revision := &json.Revision{}
	if err = json.Fill(revision, res.Body); err != nil {
		return nil, err
	}
	return revision, nil
}

func newNormalizeCommand(c *cli.Context, revision string) (Command, error) {
	if len(c.Args()) != 1 {
		return nil, newCommandLineError(c)
	}
	repo, err := newRepositoryRequestInfo(c, revision, false)
	if err != nil {
		return nil, err
	}
	return &normalizeRevisionCommand{repo: repo}, nil
}
