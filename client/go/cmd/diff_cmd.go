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

// A diffCommand returns a diff of the specified path between the from revision and to revision.
type diffCommand struct {
	repo  repositoryRequestInfoWithFromTo
	style PrintStyle
}

func (dc *diffCommand) execute(c *cli.Context) error {
	repo := dc.repo
	u, _ := url.Parse(
		repo.remote.String() + "projects/" + repo.projectName + "/repositories/" + repo.repositoryName +
			"/diff" + repo.repositoryPath)
	values := url.Values{}
	values.Set("from", repo.from)
	values.Set("to", repo.to)
	u.RawQuery = values.Encode()
	req := &http.Request{Method: http.MethodGet, URL: u}

	res, err := client.New().Do(req, c)
	if err != nil {
		return err
	}

	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("cannot get the diff of /%s/%s%s from: %q, to: %q (status: %s)",
			repo.projectName, repo.repositoryName, repo.repositoryPath,
			repo.from, repo.to, res.Status)
	}
	changes := []json.Change{}
	if err = json.Fill(&changes, res.Body); err != nil {
		return err
	}

	for _, change := range changes {
		fmt.Println(change.Content)
	}
	return nil
}

// newDiffCommand creates the diffCommand. If the revision is not specified, from revision will be 1 and
// to revision will be head by default.
func newDiffCommand(c *cli.Context, from, to string, style PrintStyle) (Command, error) {
	repo, err := newRepositoryRequestInfoWithFromTo(c, from, to)
	if err != nil {
		return nil, err
	}
	return &diffCommand{repo: repo, style: style}, nil
}
