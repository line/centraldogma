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
	"errors"
	"fmt"
	"net/http"
	"net/url"

	"github.com/line/centraldogma/client/go/client"
	"github.com/line/centraldogma/client/go/json"
	"github.com/urfave/cli"
)

// An lsProjectCommand list all the projects on the remote Central Dogma server.
type lsProjectCommand struct {
	remote *url.URL
	style  PrintStyle
}

func (lsp *lsProjectCommand) execute(c *cli.Context) error {
	u, _ := url.Parse(lsp.remote.String() + "projects")
	req := &http.Request{Method: http.MethodGet, URL: u}
	res, err := client.New().Do(req, c)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return errors.New("cannot get the list of projects")
	}

	var data []json.Project
	if err = json.Fill(&data, res.Body); err != nil {
		return err
	}
	printWithStyle(data, lsp.style)
	return nil
}

// An lsRepositoryCommand list all the repositories under the specified projectName
// on the remote Central Dogma server.
type lsRepositoryCommand struct {
	remote      *url.URL
	projectName string
	style       PrintStyle
}

func (lsr *lsRepositoryCommand) execute(c *cli.Context) error {
	u, _ := url.Parse(lsr.remote.String() + "projects/" + lsr.projectName + "/repositories")
	req := &http.Request{Method: http.MethodGet, URL: u}
	res, err := client.New().Do(req, c)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("cannot get the list of repositories in %s", lsr.projectName)
	}

	var data []json.Repository
	if err = json.Fill(&data, res.Body); err != nil {
		return err
	}
	printWithStyle(data, lsr.style)
	return nil
}

// An lsPathCommand list the specified path which is
// {repo.projectName}/{repo.repositoryName}/{repo.repositoryPath} on the remote Central Dogma server.
type lsPathCommand struct {
	repo  repositoryRequestInfo
	style PrintStyle
}

func (lsp *lsPathCommand) execute(c *cli.Context) error {
	repo := lsp.repo
	u, _ := url.Parse(
		repo.remote.String() + "projects/" + repo.projectName + "/repositories/" + repo.repositoryName +
			"/tree/revisions/" + repo.revision + repo.repositoryPath)
	req := &http.Request{Method: http.MethodGet, URL: u}
	res, err := client.New().Do(req, c)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("cannot get the list of files in the /%s/%s%s revision: %q (status: %s)",
			repo.projectName, repo.repositoryName, repo.repositoryPath, repo.revision, res.Status)
	}

	data := []json.Entry{}
	if err = json.Fill(&data, res.Body); err != nil {
		return err
	}
	printWithStyle(data, lsp.style)
	return nil
}

// newLSCommand creates one of the ls project, repository, and path commands according to the
// command arguments from the CLI. If the revision is not specified, head will be set by default.
func newLSCommand(c *cli.Context, revision string, style PrintStyle) (Command, error) {
	remoteURI, err := getRemoteURI(c.Parent().String("connect"))
	if err != nil {
		return nil, err
	}
	ca := c.Args()
	if len(ca) > 1 {
		return nil, newCommandLineError(c)
	}

	split := SplitPath(ca.First())
	if len(split) == 0 {
		return &lsProjectCommand{remote: remoteURI, style: style}, nil
	}
	if len(split) == 1 {
		pName := split[0]
		return &lsRepositoryCommand{remote: remoteURI, projectName: pName, style: style}, nil
	}

	// lsPathCommand from now on
	repo := repositoryRequestInfo{
		remote: remoteURI, projectName: split[0],
		repositoryName: split[1], repositoryPath: "/", revision: "head"}
	if len(split) == 3 {
		repo.repositoryPath = split[2]
	}
	if len(revision) != 0 {
		repo.revision = revision
	}
	return &lsPathCommand{repo: repo, style: style}, nil
}
