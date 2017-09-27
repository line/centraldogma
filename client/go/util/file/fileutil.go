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

// Package file implements file-related utilities such as creating a temp file, parsing netrc file, etc.
package file

import (
	"errors"
	"fmt"
	"io/ioutil"
	"os"
	"os/exec"
	"os/user"
	"path"
	"path/filepath"
	"runtime"
	"strings"

	"github.com/bgentry/go-netrc/netrc"
	"github.com/line/centraldogma/client/go/json"
)

func NetrcInfo() *netrc.Machine {
	if usr, err := user.Current(); err == nil {
		netrcFilePath := usr.HomeDir + string(filepath.Separator)
		if runtime.GOOS == "windows" {
			netrcFilePath += "_netrc"
		} else {
			netrcFilePath += ".netrc"
		}
		if netrc, err := netrc.ParseFile(netrcFilePath); err == nil {
			if machine := netrc.FindMachine("dogma"); machine != nil {
				return machine
			}
		}
	}
	return nil
}

func CmdToOpenEditor(filePath string) *exec.Cmd {
	editor := editor()
	cmd := exec.Command(editor, filePath)
	cmd.Stdin = os.Stdin
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	return cmd
}

func editor() string {
	editor := os.Getenv("EDITOR")
	if len(editor) != 0 {
		return editor
	}
	out, err := exec.Command("git", "var", "GIT_EDITOR").Output()
	if err == nil {
		editor = strings.TrimSpace(string(out))
		if len(editor) != 0 {
			return editor
		}
	}
	if strings.EqualFold(runtime.GOOS, "windows") {
		return "start"
	} else {
		return "vim"
	}
}

func NewTemp(tempFileName string) (string, *os.File, error) {
	// TODO(minwoox) change to a file in a fixed place like git
	tempFilePath := os.TempDir() + string(filepath.Separator) + tempFileName
	fd, err := os.Create(tempFilePath)
	if err != nil {
		return "", nil, errors.New("failed to create a temp file")
	}
	return tempFilePath, fd, nil
}

func PutIntoTempFile(entry *json.Entry) (string, error) {
	tempFilePath, fd, err := NewTemp(path.Base(entry.Path))
	if err != nil {
		return "", err
	}
	defer fd.Close()
	if strings.EqualFold(entry.Type, "JSON") {

		prettyJson, err := json.Indent([]byte(entry.Content))
		if err == nil {
			_, err = fd.WriteString(string(prettyJson.Bytes()))
			return tempFilePath, nil
		}
	}
	_, err = fd.WriteString(entry.Content)
	return tempFilePath, nil
}

func NewEntry(fileName, repositoryPath string) (*json.Entry, error) {
	fileInfo, err := os.Stat(fileName)
	if os.IsNotExist(err) {
		return nil, fmt.Errorf("%s does not exist\n", fileName)
	}
	if fileInfo.IsDir() {
		return nil, fmt.Errorf("%s is a directory\n", fileName)
	}

	entry := &json.Entry{Path: repositoryPath}
	buf, _ := ioutil.ReadFile(fileName)
	content := string(buf)
	if strings.HasSuffix(strings.ToLower(fileName), ".json") {
		entry.Type = "JSON"
		if !json.Valid(buf) {
			return nil, fmt.Errorf("not a valid JSON file: %s", fileName)
		}
	} else {
		entry.Type = "TEXT"
	}
	entry.Content = content
	return entry, nil
}
