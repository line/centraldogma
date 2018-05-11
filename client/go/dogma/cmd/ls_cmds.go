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
	"errors"
	"fmt"
	"net/http"

	"github.com/urfave/cli"
)

// A lsProjectCommand lists all the projects on the remote Central Dogma server.
type lsProjectCommand struct {
	remoteURL string
	style     PrintStyle
}

func (lsp *lsProjectCommand) execute(c *cli.Context) error {
	client, err := newDogmaClient(c, lsp.remoteURL)
	if err != nil {
		return err
	}

	projects, res, err := client.ListProjects(context.Background())
	if err != nil {
		return err
	}
	if res.StatusCode != http.StatusOK {
		return errors.New("failed to get the list of projects")
	}
	printWithStyle(projects, lsp.style)
	return nil
}

// A lsRepositoryCommand lists all the repositories under the specified projName
// on the remote Central Dogma server.
type lsRepositoryCommand struct {
	remoteURL string
	projName  string
	style     PrintStyle
}

func (lsr *lsRepositoryCommand) execute(c *cli.Context) error {
	client, err := newDogmaClient(c, lsr.remoteURL)
	if err != nil {
		return err
	}

	repos, res, err := client.ListRepositories(context.Background(), lsr.projName)
	if err != nil {
		return err
	}

	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("failed to get the list of repositories in %s", lsr.projName)
	}

	printWithStyle(repos, lsr.style)
	return nil
}

// A lsPathCommand lists the specified path which is {repo.projName}/{repo.repoName}/{repo.path}
// on the remote Central Dogma server.
type lsPathCommand struct {
	repo  repositoryRequestInfo
	style PrintStyle
}

func (lsp *lsPathCommand) execute(c *cli.Context) error {
	client, err := newDogmaClient(c, lsp.repo.remoteURL)
	if err != nil {
		return err
	}

	repos, res, err := client.ListFiles(context.Background(), lsp.repo.projName, lsp.repo.repoName,
		lsp.repo.revision, lsp.repo.path)
	if err != nil {
		return err
	}

	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("failed to get the list of files in the /%s/%s%s revision: %q (status: %s)",
			lsp.repo.projName, lsp.repo.repoName, lsp.repo.path, lsp.repo.revision,
			res.Status)
	}

	printWithStyle(repos, lsp.style)
	return nil
}

// newLSCommand creates one of the ls project, repository, and path commands according to the
// command arguments from the CLI. If the revision is not specified, -1 will be set by default.
func newLSCommand(c *cli.Context, style PrintStyle) (Command, error) {
	remoteURL, err := getRemoteURL(c.Parent().String("connect"))
	if err != nil {
		return nil, err
	}

	ca := c.Args()
	if len(ca) > 1 { // If there are no arguments, it is a list projects command.
		return nil, newCommandLineError(c)
	}

	split := splitPath(ca.First())
	if len(split) > 1 {
		repo, err := newRepositoryRequestInfo(c)
		if err != nil {
			return nil, err
		}
		return &lsPathCommand{repo: repo, style: style}, nil
	}

	if len(split) == 0 {
		return &lsProjectCommand{remoteURL: remoteURL, style: style}, nil
	}

	return &lsRepositoryCommand{remoteURL: remoteURL, projName: split[0], style: style}, nil
}
