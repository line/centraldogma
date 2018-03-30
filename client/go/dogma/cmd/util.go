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
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"html/template"
	"io/ioutil"
	"os"
	"os/exec"
	"os/user"
	"path"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"unicode"

	"github.com/bgentry/go-netrc/netrc"
	"github.com/line/centraldogma/client/go/dogma"
	"github.com/urfave/cli"
)

type commitType int

const (
	_ commitType = iota
	addition
	edition
	removal
)

var tempFileName = "commit-message.txt"

func getCommitMessage(c *cli.Context, filePath string, commitType commitType) (*dogma.CommitMessage, error) {
	message := c.String("message")
	if len(message) != 0 {
		return &dogma.CommitMessage{Summary: message}, nil
	}

	tempFilePath, fd, err := newTempFile(tempFileName)
	if err != nil {
		return nil, err
	}
	defer os.Remove(tempFilePath)
	defer fd.Close()

	tmpl, _ := template.New("").Parse(commitMessageTemplate)
	tmpl.Execute(fd, typeWithFile{FilePath: filePath, CommitType: commitType})

	cmd := cmdToOpenEditor(tempFilePath)
	if err = cmd.Start(); err != nil {
		// Failed to launch the editor.
		return messageFromCLI()
	}

	err = cmd.Wait()
	if err != nil {
		return nil, errors.New("failed to write the commit message")
	}
	return messageFrom(tempFilePath)
}

func messageFromCLI() (*dogma.CommitMessage, error) {
	reader := bufio.NewReader(os.Stdin)
	fmt.Print("Enter summary: ")
	summary, _ := reader.ReadString('\n')
	if len(summary) == 0 {
		return nil, errors.New("you must input summary")
	}
	commitMessage := &dogma.CommitMessage{Summary: summary}

	fmt.Print("\nEnter detail: ")
	detail, _ := reader.ReadString('\n')
	if len(detail) != 0 {
		commitMessage.Detail = detail
		commitMessage.Markup = "PLAINTEXT"
	}

	return commitMessage, nil
}

func messageFrom(filePath string) (*dogma.CommitMessage, error) {
	fd, err := os.Open(filePath)
	if err != nil {
		return nil, err
	}
	defer fd.Close()
	scanner := bufio.NewScanner(fd)

	summary, err := getSummary(scanner)
	if err != nil {
		return nil, err
	}
	commitMessage := &dogma.CommitMessage{Summary: summary}

	detail := getDetail(scanner)
	if len(detail) != 0 {
		commitMessage.Detail = detail
		commitMessage.Markup = "PLAINTEXT"
	}

	return commitMessage, nil
}

func getSummary(scanner *bufio.Scanner) (string, error) {
	line := ""
	for scanner.Scan() {
		line = strings.TrimSpace(scanner.Text())
		if !strings.HasPrefix(line, "#") && len(line) != 0 {
			return line, nil
		}
	}
	return "", errors.New("must input the summary")
}

func getDetail(scanner *bufio.Scanner) string {
	var buf bytes.Buffer
	passedEmptyLinesUnderSummary := false
	for scanner.Scan() {
		line := strings.TrimRightFunc(scanner.Text(), unicode.IsSpace)
		if strings.HasPrefix(line, "#") {
			continue
		}
		if len(line) == 0 && !passedEmptyLinesUnderSummary {
			continue
		} else {
			passedEmptyLinesUnderSummary = true
		}
		buf.WriteString(line)
		buf.WriteString("\n")
	}
	// remove trailing empty lines
	regex, _ := regexp.Compile("\\n{2,}\\z")
	return regex.ReplaceAllString(buf.String(), "")
}

type typeWithFile struct {
	FilePath   string
	CommitType commitType
}

var commitMessageTemplate = `
# Please enter the commit message for your changes. Lines starting\n
# with '#' will be ignored, and an empty message aborts the commit.\n
#
# Changes to be committed:
#   {{if eq .commitType 1}}new file{{else if eq .commitType 2}}modified{{else}}deleted{{end}}: {{.FilePath}}
#
`

func netrcInfo(machineName string) *netrc.Machine {
	if usr, err := user.Current(); err == nil {
		netrcFilePath := usr.HomeDir + string(filepath.Separator)
		if runtime.GOOS == "windows" {
			netrcFilePath += "_netrc"
		} else {
			netrcFilePath += ".netrc"
		}
		if netrc, err := netrc.ParseFile(netrcFilePath); err == nil {
			if machine := netrc.FindMachine(machineName); machine != nil {
				return machine
			}
		}
	}
	return nil
}

func cmdToOpenEditor(filePath string) *exec.Cmd {
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

func newTempFile(tempFileName string) (string, *os.File, error) {
	// TODO(minwoox) change to a file in a fixed place like git
	tempFilePath := os.TempDir() + string(filepath.Separator) + tempFileName
	fd, err := os.Create(tempFilePath)
	if err != nil {
		return "", nil, errors.New("failed to create a temp file")
	}
	return tempFilePath, fd, nil
}

func putIntoTempFile(entry *dogma.Entry) (string, error) {
	tempFilePath, fd, err := newTempFile(path.Base(entry.Path))
	if err != nil {
		return "", err
	}
	defer fd.Close()
	if entry.Type == dogma.JSON {
		b, err := marshalIndent(entry.Content)
		if err != nil {
			return "", err
		}

		if _, err := fd.Write(b); err != nil {
			return "", err
		}
		return tempFilePath, nil
	} else if entry.Type == dogma.Text {
		if str, ok := entry.Content.(string); ok {
			_, err = fd.WriteString(str)
			if err != nil {
				return "", err
			}
		} else {
			return "", fmt.Errorf("failed to save the content: %v", entry.Content)
		}
	}
	return tempFilePath, nil
}

func newUpsertChangeFromFile(fileName, repositoryPath string) (*dogma.Change, error) {
	fileInfo, err := os.Stat(fileName)
	if os.IsNotExist(err) {
		return nil, fmt.Errorf("%s does not exist\n", fileName)
	}
	if fileInfo.IsDir() {
		return nil, fmt.Errorf("%s is a directory\n", fileName)
	}

	change := &dogma.Change{Path: repositoryPath}
	buf, err := ioutil.ReadFile(fileName)
	if err != nil {
		return nil, err
	}

	if strings.HasSuffix(strings.ToLower(fileName), ".json") {
		change.Type = dogma.UpsertJSON
		if !json.Valid(buf) {
			return nil, fmt.Errorf("not a valid JSON file: %s", fileName)
		}
		var temp interface{}
		json.Unmarshal(buf, &temp)
		change.Content = temp
	} else {
		change.Content = string(buf)
		change.Type = dogma.UpsertText
	}
	return change, nil
}

func marshalIndent(data interface{}) ([]byte, error) {
	return json.MarshalIndent(data, "", "    ")
}
