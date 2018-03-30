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
	"net/http"

	"github.com/urfave/cli"
)

// A diffCommand returns a diff of the specified path between the from revision and to revision.
type diffCommand struct {
	repo  repositoryRequestInfoWithFromTo
	style PrintStyle
}

func (d *diffCommand) execute(c *cli.Context) error {
	repo := d.repo
	client, err := newDogmaClient(c, repo.remoteURL)
	if err != nil {
		return err
	}

	changes, res, err := client.GetDiffs(
		context.Background(), repo.projName, repo.repoName, repo.from, repo.to, repo.path)
	if err != nil {
		return err
	}
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("failed to get the diff of /%s/%s%s from: %q, to: %q (status: %s)",
			repo.projName, repo.repoName, repo.path, repo.from, repo.to, res.Status)
	}

	for _, change := range changes {
		data, err := marshalIndent(change)
		if err != nil {
			return err
		}
		fmt.Printf("%s\n", data)
	}

	return nil
}

// newDiffCommand creates the diffCommand. If the from and to are not specified, from revision will be 1 and
// to revision will be -1 respectively.
func newDiffCommand(c *cli.Context, style PrintStyle) (Command, error) {
	repo, err := newRepositoryRequestInfo(c)
	if err != nil {
		return nil, err
	}

	repoWithFromTo := repositoryRequestInfoWithFromTo{remoteURL: repo.remoteURL, projName: repo.projName,
		repoName: repo.repoName, path: repo.path}

	if from := c.String("from"); len(from) != 0 {
		repoWithFromTo.from = from
	} else {
		repoWithFromTo.from = "1"
	}
	if to := c.String("to"); len(to) != 0 {
		repoWithFromTo.to = to
	} else {
		repoWithFromTo.to = "-1"
	}
	return &diffCommand{repo: repoWithFromTo, style: style}, nil
}
