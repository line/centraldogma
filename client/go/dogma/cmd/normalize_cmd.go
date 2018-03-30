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

type normalizeRevisionCommand struct {
	repo repositoryRequestInfo
}

func (nr *normalizeRevisionCommand) execute(c *cli.Context) error {
	repo := nr.repo
	client, err := newDogmaClient(c, repo.remoteURL)
	if err != nil {
		return err
	}

	normalized, res, err := client.NormalizeRevision(context.Background(), repo.projName, repo.repoName, repo.revision)
	if err != nil {
		return err
	}
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("failed to normalize /%s/%s revision: %q (status: %s)",
			repo.projName, repo.repoName, repo.revision, res.Status)
	}

	fmt.Printf("normalized revision: %v\n", normalized)
	return nil
}

func newNormalizeCommand(c *cli.Context) (Command, error) {
	repo, err := newRepositoryRequestInfo(c)
	if err != nil {
		return nil, err
	}
	return &normalizeRevisionCommand{repo: repo}, nil
}
