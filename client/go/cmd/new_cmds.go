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
	"path"
	"strings"

	"github.com/line/centraldogma/client/go/client"
	"github.com/line/centraldogma/client/go/json"
	"github.com/line/centraldogma/client/go/util/commit"
	"github.com/line/centraldogma/client/go/util/file"
	"github.com/urfave/cli"
)

// An newProjectCommand creates a project with the specified projectName on the remote Central Dogma server.
type newProjectCommand struct {
	remote      *url.URL
	projectName string
}

func (ap *newProjectCommand) execute(c *cli.Context) error {
	buf := new(bytes.Buffer)
	json.NewEncoder(buf).Encode(json.Project{Name: ap.projectName})

	u, _ := url.Parse(ap.remote.String() + "projects")
	req := &http.Request{Method: http.MethodPost, URL: u, Body: ioutil.NopCloser(buf)}
	req.Header = http.Header{"Content-Type": {"application/json"}}
	res, err := client.New().Do(req, c)
	if err != nil {
		return err
	}
	if res.StatusCode != http.StatusCreated {
		return fmt.Errorf("cannot create %s project (status: %s)", ap.projectName, res.Status)
	}
	fmt.Printf("Created: /%s\n", ap.projectName)
	return nil
}

// An newRepositoryCommand creates a repository with the specified repository name under the project
// on the remote Central Dogma server.
type newRepositoryCommand struct {
	remote      *url.URL
	projectName string
	repository  *json.Repository
}

func (ar *newRepositoryCommand) execute(c *cli.Context) error {
	buf := new(bytes.Buffer)
	json.NewEncoder(buf).Encode(ar.repository)

	u, _ := url.Parse(ar.remote.String() + "projects/" + ar.projectName + "/repositories")
	req := &http.Request{Method: http.MethodPost, URL: u, Body: ioutil.NopCloser(buf)}
	req.Header = http.Header{"Content-Type": {"application/json"}}
	res, err := client.New().Do(req, c)
	if err != nil {
		return err
	}
	if res.StatusCode != http.StatusCreated {
		return fmt.Errorf("cannot create %s in %s (status: %s)",
			ar.repository.Name, ar.projectName, res.Status)
	}
	fmt.Printf("Created: /%s/%s\n", ar.projectName, ar.repository.Name)
	return nil
}

func newNewCommand(c *cli.Context) (Command, error) {
	ca := c.Args()
	argLen := len(ca)
	if argLen != 1 {
		return nil, newCommandLineError(c)
	}
	remoteURI, err := getRemoteURI(c.Parent().String("connect"))
	if err != nil {
		return nil, err
	}

	split := SplitPath(ca.First())
	if len(split) == 1 {
		return &newProjectCommand{remote: remoteURI, projectName: split[0]}, nil
	}
	if len(split) == 2 {
		pName := split[0]
		rName := split[1]
		return &newRepositoryCommand{
			remote: remoteURI, projectName: pName, repository: &json.Repository{Name: rName}}, nil
	}

	return nil, newCommandLineError(c)
}

// An putFileCommand puts a local file to the specified path which is
// {repo.projectName}/{repo.repositoryName}/{repo.repositoryPath} on the remote Central Dogma server.
// If the repositoryPath is not specified, the path will be / followed by the file name of the localFilePath.
// If the repositoryPath ends with /, the file name of the localFilePath will be added to that /.
// If the repositoryPath is specified with a file name, the file will be added as the specified name.
type putFileCommand struct {
	repo          repositoryRequestInfo
	localFilePath string
}

func (af *putFileCommand) execute(c *cli.Context) error {
	repo := af.repo
	entry, err := file.NewEntry(af.localFilePath, repo.repositoryPath)
	if err != nil {
		return err
	}
	commitMessage, err := commit.GetMessage(af.localFilePath, commit.Add)
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

	if res.StatusCode != http.StatusCreated {
		return fmt.Errorf("cannot put %s to /%s/%s%s revision: %q (status: %s)",
			af.localFilePath, repo.projectName, repo.repositoryName,
			repo.repositoryPath, repo.revision, res.Status)
	}
	fmt.Printf("Put: /%s/%s%s\n", repo.projectName, repo.repositoryName, repo.repositoryPath)
	return nil
}

func newPutCommand(c *cli.Context, revision string) (Command, error) {
	ca := c.Args()
	argLen := len(ca)
	if argLen != 2 {
		return nil, newCommandLineError(c)
	}
	remoteURI, err := getRemoteURI(c.Parent().String("connect"))
	if err != nil {
		return nil, err
	}

	split := SplitPath(ca.First())
	if len(split) < 2 { // need at least projectName and repositoryName
		return nil, newCommandLineError(c)
	}

	repo := repositoryRequestInfo{
		remote: remoteURI, projectName: split[0], repositoryName: split[1], revision: "head"}
	if len(revision) != 0 {
		repo.revision = revision
	}

	fileName := ca.Get(1)
	if len(fileName) == 0 {
		return nil, newCommandLineError(c)
	}

	rPath := ""
	if len(split) == 3 {
		rPath = split[2]
		if strings.HasSuffix(rPath, "/") {
			rPath += path.Base(fileName)
		}
	} else {
		rPath += "/" + path.Base(fileName)
	}
	repo.repositoryPath = rPath
	return &putFileCommand{repo: repo, localFilePath: fileName}, nil
}
