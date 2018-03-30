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
	"path"
	"strings"

	"github.com/line/centraldogma/client/go/dogma"
	"github.com/urfave/cli"
)

// A newProjectCommand creates a project with the specified project name on the remote Central Dogma server.
type newProjectCommand struct {
	remoteURL string
	name      string
}

func (np *newProjectCommand) execute(c *cli.Context) error {
	client, err := newDogmaClient(c, np.remoteURL)
	if err != nil {
		return err
	}

	_, res, err := client.CreateProject(context.Background(), np.name)
	if err != nil {
		return err
	}
	if res.StatusCode != http.StatusCreated {
		return fmt.Errorf("failed to create %s (status: %s)", np.name, res.Status)
	}

	fmt.Printf("Created: /%s\n", np.name)
	return nil
}

// A newRepositoryCommand creates a repository with the specified repository name under the project
// on the remote Central Dogma server.
type newRepositoryCommand struct {
	remoteURL string
	projName  string
	repoName  string
}

func (nr *newRepositoryCommand) execute(c *cli.Context) error {
	client, err := newDogmaClient(c, nr.remoteURL)
	if err != nil {
		return err
	}

	_, res, err := client.CreateRepository(context.Background(), nr.projName, nr.repoName)
	if err != nil {
		return err
	}
	if res.StatusCode != http.StatusCreated {
		return fmt.Errorf("failed to create %s (status: %s)", nr.repoName, res.Status)
	}
	fmt.Printf("Created: /%s/%s\n", nr.projName, nr.repoName)
	return nil
}

func newNewCommand(c *cli.Context) (Command, error) {
	if len(c.Args()) != 1 {
		return nil, newCommandLineError(c)
	}
	remoteURL, err := getRemoteURL(c.Parent().String("connect"))
	if err != nil {
		return nil, err
	}

	split := splitPath(c.Args().First())
	if len(split) > 2 {
		return nil, newCommandLineError(c)
	}

	if len(split) == 1 {
		return &newProjectCommand{remoteURL: remoteURL, name: split[0]}, nil
	}

	return &newRepositoryCommand{remoteURL: remoteURL, projName: split[0], repoName: split[1]}, nil
}

// A putFileCommand puts a local file to the specified path which is
// {repo.projName}/{repo.repoName}/{repo.path} on the remote Central Dogma server.
// If the path is not specified, the path will be / followed by the file name of the localFilePath.
// If the path ends with /, the file name of the localFilePath will be added to that /.
// If the path is specified with a file name, the file will be added as the specified name.
type putFileCommand struct {
	repo          repositoryRequestInfo
	localFilePath string
}

func (pf *putFileCommand) execute(c *cli.Context) error {
	repo := pf.repo
	client, err := newDogmaClient(c, repo.remoteURL)
	if err != nil {
		return err
	}

	change, err := newUpsertChangeFromFile(pf.localFilePath, repo.path)
	if err != nil {
		return err
	}

	commitMessage, err := getCommitMessage(c, pf.localFilePath, addition)
	if err != nil {
		return err
	}

	_, res, err := client.Push(context.Background(),
		repo.projName, repo.repoName, repo.revision, commitMessage, []*dogma.Change{change})
	if err != nil {
		return err
	}
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("failed to put %s to /%s/%s%s revision: %q (status: %s)",
			pf.localFilePath, repo.projName, repo.repoName,
			repo.path, repo.revision, res.Status)
	}
	fmt.Printf("Put: /%s/%s%s\n", repo.projName, repo.repoName, repo.path)
	return nil
}

func newPutCommand(c *cli.Context) (Command, error) {
	if len(c.Args()) != 2 {
		return nil, newCommandLineError(c)
	}

	repo, err := newRepositoryRequestInfo(c)
	if err != nil {
		return nil, err
	}

	fileName := c.Args().Get(1)
	if len(fileName) == 0 {
		return nil, newCommandLineError(c)
	}

	baseFileName := path.Base(fileName)
	if baseFileName != "/" && baseFileName != "." {
		if strings.HasSuffix(repo.path, "/") {
			repo.path = repo.path + baseFileName
		}
	}
	return &putFileCommand{repo: repo, localFilePath: fileName}, nil
}
