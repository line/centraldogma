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
	"os"
	"path"
	"regexp"
	"strconv"

	"github.com/urfave/cli"
)

// A getFileCommand fetches the content of the file in the specified path matched by the
// <a href="https://github.com/json-path/JsonPath/blob/master/README.md">JSON path expressions</a>
// with the specified revision.
type getFileCommand struct {
	repo          repositoryRequestInfo
	localFilePath string
	expression    string
}

func (ff *getFileCommand) execute(c *cli.Context) error {
	return execute0(c, ff.repo, ff.localFilePath, ff.expression, saveAction)
}

// A catFileCommand shows the content of the file in the specified path matched by the
// <a href="https://github.com/json-path/JsonPath/blob/master/README.md">JSON path expressions</a>
// with the specified revision.
type catFileCommand struct {
	repo       repositoryRequestInfo
	expression string
}

func (cf *catFileCommand) execute(c *cli.Context) error {
	return execute0(c, cf.repo, "", cf.expression, printAction)
}

var saveAction = func(content, filePath string) error {
	filePath = creatableFilePath(filePath, 1)
	fd, err := os.Create(filePath)
	if err != nil {
		return err
	}
	defer fd.Close()
	_, err = fd.WriteString(content)
	if err != nil {
		return err
	}
	fmt.Printf("Downloaded: %s\n", path.Base(filePath))
	return nil
}

func creatableFilePath(filePath string, inc int) string {
	regex, _ := regexp.Compile("\\.[0-9]*$")
	if _, err := os.Stat(filePath); !os.IsNotExist(err) {
		if inc == 1 {
			filePath += "."
		}
		startIndex := regex.FindStringIndex(filePath)
		filePath = filePath[0:startIndex[0]+1] + strconv.Itoa(inc)
		inc++
		return creatableFilePath(filePath, inc)
	}
	return filePath
}

var printAction = func(content, _ string) error {
	fmt.Printf("%s\n", content)
	return nil
}

func execute0(c *cli.Context, repo repositoryRequestInfo, localFilePath, expression string,
	action func(content, filePath string) error) error {
	entryWithRevision, err := getRemoteFileEntry(
		c, repo.remote, repo.projectName, repo.repositoryName, repo.repositoryPath, repo.revision, expression)
	if err != nil {
		return err
	}
	if err = action(entryWithRevision.File.Content, localFilePath); err != nil {
		return err
	}
	return nil
}

// newGetCommand creates the fetchCommand. If the localFilePath is not specified, the file name of the path
// will be set by default. If the revision is not specified, head will be set by default.
func newGetCommand(c *cli.Context, revision, expression string) (Command, error) {
	if len(c.Args()) == 0 || len(c.Args()) > 2 {
		return nil, newCommandLineError(c)
	}

	repo, err := newRepositoryRequestInfo(c, revision, true)
	if err != nil {
		return nil, err
	}

	localFilePath := ""
	if len(c.Args()) == 2 {
		localFilePath = c.Args().Get(1)
		if len(localFilePath) == 0 {
			return nil, newCommandLineError(c)
		}
	} else {
		localFilePath = path.Base(repo.repositoryPath)
	}

	return &getFileCommand{repo: repo, localFilePath: localFilePath, expression: expression}, nil
}

// newCatCommand creates the catCommand. If the revision is not specified, head will be set by default.
func newCatCommand(c *cli.Context, revision, expression string) (Command, error) {
	if len(c.Args()) != 1 {
		return nil, newCommandLineError(c)
	}
	repo, err := newRepositoryRequestInfo(c, revision, true)
	if err != nil {
		return nil, err
	}
	return &catFileCommand{repo: repo, expression: expression}, nil
}
