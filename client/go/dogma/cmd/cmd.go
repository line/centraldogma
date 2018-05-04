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

// Package cmd implements commands that execute HTTP API to download, add, create, modify, or delete the
// specified resources on the Central Dogma server.
// Commands are created according to the input from the CLI and executed.
package cmd

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
	"strings"
	"syscall"

	"net/url"

	"github.com/line/centraldogma/client/go/dogma"
	"github.com/urfave/cli"
	"golang.org/x/crypto/ssh/terminal"
)

// Command is the common interface implemented by all commands.
type Command interface {
	// execute executes the command
	execute(c *cli.Context) error
}

func getRemoteURL(remoteURL string) (string, error) {
	if len(remoteURL) == 0 {
		fmt.Println("Enter server address: (e.g. http://example.com:36462)")
		scanner := bufio.NewScanner(os.Stdin)
		if !scanner.Scan() {
			return "", errors.New("you must input specific address (e.g. http://example.com:36462)")
		}
		line := strings.TrimSpace(scanner.Text())
		if _, err := url.Parse(line); err != nil {
			return "", errors.New("invalid server address")
		}
		return line, nil
	}
	return remoteURL, nil
}

type repositoryRequestInfo struct {
	remoteURL string
	projName  string
	repoName  string
	path      string
	revision  string
}

// newRepositoryRequestInfo creates a repositoryRequestInfo.
func newRepositoryRequestInfo(c *cli.Context) (repositoryRequestInfo, error) {
	repo := repositoryRequestInfo{path: "/", revision: "-1"}
	if len(c.Args()) == 0 {
		return repo, newCommandLineError(c)
	}
	split := splitPath(c.Args().First())
	if len(split) < 2 { // Need at least projName and repoName.
		return repo, newCommandLineError(c)
	}

	remoteURL, err := getRemoteURL(c.Parent().String("connect"))
	if err != nil {
		return repo, err
	}
	repo.remoteURL = remoteURL
	repo.projName = split[0]
	repo.repoName = split[1]

	if len(split) > 2 && len(split[2]) != 0 {
		repo.path = split[2]
	}

	revision := c.String("revision")
	if len(revision) != 0 {
		repo.revision = revision
	}
	return repo, nil
}

// splitPath parses the path into projName, repoName and path.
func splitPath(fullPath string) []string {
	endsWithSlash := false
	if strings.HasSuffix(fullPath, "/") {
		endsWithSlash = true
	}

	split := strings.Split(fullPath, "/")
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
	remoteURL string
	projName  string
	repoName  string
	path      string
	from      string
	to        string
}

// getRemoteFileEntry downloads the entry of the specified remote path. If the jsonPaths
// is specified, only the applied content of the jsonPaths will be downloaded.
func getRemoteFileEntry(c *cli.Context,
	remoteURL, projName, repoName, repoPath, revision string, jsonPaths []string) (*dogma.Entry, error) {
	client, err := newDogmaClient(c, remoteURL)
	if err != nil {
		return nil, err
	}

	query := createQuery(repoPath, jsonPaths)
	entry, res, err := client.GetFile(context.Background(), projName, repoName, revision, query)
	if err != nil {
		return nil, err
	}

	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("failed to get the file: /%s/%s%s revision: %q (status: %s)",
			projName, repoName, repoPath, revision, res.Status)
	}

	return entry, nil
}

func newDogmaClient(c *cli.Context, baseURL string) (client *dogma.Client, err error) {
	enabled, err := checkIfSecurityEnabled(baseURL)
	if err != nil {
		return nil, err
	}

	if !enabled {
		// Create a client with the anonymous token.
		return dogma.NewClientWithToken(baseURL, "anonymous")
	}

	token := c.Parent().String("token")
	if len(token) != 0 {
		if client, err = dogma.NewClientWithToken(baseURL, token); err != nil {
			return nil, err
		}
	} else {
		// Get username and password from netrc file or prompt.
		username, password, err := usernameAndPassword(c)
		if client, err = dogma.NewClient(baseURL, username, password); err != nil {
			return nil, err
		}
	}

	return client, nil
}

func createQuery(repoPath string, jsonPaths []string) *dogma.Query {
	if len(jsonPaths) != 0 && strings.HasSuffix(strings.ToLower(repoPath), "json") {
		return &dogma.Query{Path: repoPath, Type: dogma.JSONPath, Expressions: jsonPaths}
	} else {
		return &dogma.Query{Path: repoPath, Type: dogma.Identity}
	}
}

func checkIfSecurityEnabled(baseURL string) (bool, error) {
	// Create a client with the anonymous token just to check the security is enabled.
	client, err := dogma.NewClientWithToken(baseURL, "anonymous")
	if err != nil {
		return false, err
	}
	return client.SecurityEnabled()
}

func usernameAndPassword(c *cli.Context) (username string, password string, err error) {
	username = c.Parent().String("login")
	if len(username) != 0 {
		if password, err = getPassword(); err != nil {
			return "", "", err
		}
		return username, password, nil
	}

	machine := netrcInfo(c.Parent().String("connect"))
	if machine != nil {
		username = machine.Login
		password = machine.Password
		if len(username) == 0 || len(password) == 0 {
			return "", "", fmt.Errorf("netrc file doesn't have enough information (username:%q)", username)
		}
		return username, password, nil
	}

	if username, err = getUsername(); err != nil {
		return "", "", err
	}
	if password, err = getPassword(); err != nil {
		return "", "", err
	}
	return username, password, nil
}

func getUsername() (string, error) {
	fmt.Print("Enter username: ")
	scanner := bufio.NewScanner(os.Stdin)
	if !scanner.Scan() {
		return "", errors.New("you must input username")
	}
	line := strings.TrimSpace(scanner.Text())
	if len(line) == 0 {
		return "", errors.New("you must input username")
	}
	return line, nil
}

func getPassword() (string, error) {
	fmt.Print("Enter password: ")
	bytePassword, err := terminal.ReadPassword(int(syscall.Stdin))
	if err != nil {
		return "", err
	}
	fmt.Println()
	password := string(bytePassword)
	if len(password) == 0 {
		return "", errors.New("you must input password")
	}
	return password, nil
}
