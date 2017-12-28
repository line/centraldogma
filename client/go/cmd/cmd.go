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

// Package cmd implements commands that execute rest API to download, add, create, modify, or delete the
// specified resources on the Central Dogma server.
// Commands are created according to the input from the CLI and executed.
package cmd

import (
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"github.com/line/centraldogma/client/go/client"
	"github.com/line/centraldogma/client/go/json"
	"github.com/line/centraldogma/client/go/service"
	"github.com/line/centraldogma/client/go/util/file"
	"github.com/urfave/cli"
)

// Command is the common interface implemented by all commands.
type Command interface {
	// execute execute the command
	execute(c *cli.Context) error
}

func getRemoteURI(host string) (*url.URL, error) {
	if len(host) != 0 {
		return normalizedURI(host)
	} else {
		machine := file.NetrcInfo()
		if machine != nil && len(machine.Name) != 0 {
			return normalizedURI(machine.Name)
		}
	}
	return url.Parse(
		service.DefaultScheme + "://" + service.DefaultHostName + ":" + strconv.Itoa(service.DefaultPort) +
			service.DefaultPathPrefix)
}

func normalizedURI(host string) (*url.URL, error) {
	if !strings.HasPrefix(host, "http") {
		host = service.DefaultScheme + "://" + host
	}
	if strings.HasSuffix(host, "/") {
		host = host[:len(host)-1]
	}

	parsedURL, err := url.Parse(host)
	if err != nil {
		return nil, err
	}
	if len(parsedURL.Scheme) == 0 {
		host = service.DefaultScheme + "://" + host
	}
	port := parsedURL.Port()
	if len(port) == 0 {
		host += ":" + strconv.Itoa(service.DefaultPort)
	}
	host += service.DefaultPathPrefix
	return url.Parse(host)
}

type repositoryRequestInfo struct {
	remote         *url.URL
	projectName    string
	repositoryName string
	repositoryPath string
	revision       string
}

// newRepositoryRequestInfo creates a repositoryRequestInfo. If the revision is not specified,
// it will be set to "head" which means most recent revision.
func newRepositoryRequestInfo(c *cli.Context, revision string, needRepositoryPath bool) (
	repositoryRequestInfo, error) {
	repo := repositoryRequestInfo{}
	remoteURI, err := getRemoteURI(c.Parent().String("connect"))
	if err != nil {
		return repo, err
	}

	split := SplitPath(c.Args().First())

	repositoryPath := ""
	if needRepositoryPath {
		if len(split) < 3 { // need projectName, repositoryName, and repositoryPath
			return repo, newCommandLineError(c)
		}
		repositoryPath = split[2]
	} else {
		if len(split) < 2 { // need projectName and repositoryName
			return repo, newCommandLineError(c)
		}
	}

	repo.remote = remoteURI
	repo.projectName = split[0]
	repo.repositoryName = split[1]
	repo.repositoryPath = repositoryPath
	repo.revision = "head"
	if len(revision) != 0 {
		repo.revision = revision
	}
	return repo, nil
}

// SplitPath parses the path into projectName, repositoryName and repositoryPath.
func SplitPath(wholePath string) []string {
	endsWithSlash := false
	if strings.HasSuffix(wholePath, "/") {
		endsWithSlash = true
	}

	split := strings.Split(wholePath, "/")
	var ret []string
	for _, str := range split {
		if str != "" {
			ret = append(ret, str)
		}
	}
	if len(ret) <= 2 {
		return ret
	}
	repositoryPath := "/" + strings.Join(ret[2:], "/")
	if endsWithSlash {
		repositoryPath += "/"
	}
	return append(ret[:2], repositoryPath)
}

type repositoryRequestInfoWithFromTo struct {
	remote         *url.URL
	projectName    string
	repositoryName string
	repositoryPath string
	from           string
	to             string
}

// newRepositoryRequestInfoWithFromTo creates a repositoryRequestInfoWithFromTo. If the from and to revision
// are not specified, it will be set to "1" and "head" respectively which means the whole range of revisions.
func newRepositoryRequestInfoWithFromTo(c *cli.Context, from, to string) (
	repositoryRequestInfoWithFromTo, error) {
	repo := repositoryRequestInfoWithFromTo{}
	remoteInfo, err := getRemoteURI(c.Parent().String("connect"))
	if err != nil {
		return repo, err
	}
	repo.remote = remoteInfo

	ca := c.Args()
	argLen := len(ca)
	if argLen != 1 {
		return repo, newCommandLineError(c)
	}

	split := SplitPath(ca.First())

	if len(split) < 2 { // need at least projectName and repositoryName
		return repo, newCommandLineError(c)
	}
	repo.projectName = split[0]
	repo.repositoryName = split[1]

	repositoryPath := "/"
	if len(split) == 3 {
		repositoryPath = split[2]
	}
	repo.repositoryPath = repositoryPath

	// TODO validate from to
	if len(from) == 0 {
		from = "1"
	}
	repo.from = from
	if len(to) == 0 {
		to = "head"
	}
	repo.to = to
	return repo, nil
}

// getRemoteFileEntry downloads the entry of the specified remote path. If the JSON path expression
// is specified, only the applied content of the expression will be downloaded.
func getRemoteFileEntry(c *cli.Context, remoteURI *url.URL, projectName, repositoryName, repositoryPath,
	revision, expression string) (*json.EntryWithRevision, error) {
	u, _ := url.Parse(
		remoteURI.String() + "projects/" + projectName + "/repositories/" + repositoryName +
			"/files/revisions/" + revision + repositoryPath)
	if len(expression) != 0 {
		values := url.Values{}
		values.Set("queryType", "JSON_PATH")
		values.Set("expression", expression)
		u.RawQuery = values.Encode()
	}
	req := &http.Request{Method: http.MethodGet, URL: u}
	res, err := client.New().Do(req, c)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()

	if res.StatusCode != http.StatusOK {
		message := fmt.Sprintf("cannot get the file: /%s/%s%s revision: %q (status: %s)",
			projectName, repositoryName, repositoryPath, revision, res.Status)
		return nil, errors.New(message)
	}

	entryWithRevision := &json.EntryWithRevision{}
	if err = json.Fill(entryWithRevision, res.Body); err != nil {
		return nil, err
	}
	return entryWithRevision, nil
}
