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

type logCommand struct {
	repo  repositoryRequestInfoWithFromTo
	style PrintStyle
}

func (l *logCommand) execute(c *cli.Context) error {
	repo := l.repo
	client, err := newDogmaClient(c, repo.remoteURL)
	if err != nil {
		return err
	}

	commits, res, err := client.GetHistory(
		context.Background(), repo.projName, repo.repoName, repo.from, repo.to, repo.path)
	if err != nil {
		return err
	}
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("failed to get the commit logs of /%s/%s%s from: %q, to: %q (status: %s)",
			repo.projName, repo.repoName, repo.path, repo.from, repo.to, res.Status)
	}

	printWithStyle(commits, l.style)
	return nil
}

// newLogCommand creates the logCommand.
func newLogCommand(c *cli.Context, style PrintStyle) (Command, error) {
	repo, err := newRepositoryRequestInfo(c)
	if err != nil {
		return nil, err
	}

	repoWithFromTo := repositoryRequestInfoWithFromTo{remoteURL: repo.remoteURL, projName: repo.projName,
		repoName: repo.repoName, path: repo.path}

	from := c.String("from")
	to := c.String("to")
	if len(from) == 0 && len(to) == 0 {
		from = "-1"
		to = "1"
	} else if len(from) != 0 && len(to) == 0 {
		to = "1"
	}
	repoWithFromTo.from = from
	repoWithFromTo.to = to

	return &logCommand{repo: repoWithFromTo, style: style}, nil
}
