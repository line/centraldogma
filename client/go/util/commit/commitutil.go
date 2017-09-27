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

// Package commit implements utilities that parse the input and make commit messages.
package commit

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"html/template"
	"os"
	"regexp"
	"strings"
	"unicode"

	"github.com/line/centraldogma/client/go/json"
	"github.com/line/centraldogma/client/go/util/file"
)

type CommitType int

const (
	_ CommitType = iota
	Add
	Edit
	Remove
)

var tempFileName = "commit-message.txt"

func GetMessage(filePathToBeChanged string, commitType CommitType) (*json.CommitMessage, error) {
	tempFilePath, fd, err := file.NewTemp(tempFileName)
	if err != nil {
		return nil, err
	}
	defer os.Remove(tempFilePath)
	tmpl, _ := template.New("").Parse(commitMessageTemplate)
	tmpl.Execute(fd, typeWithFile{FilePath: filePathToBeChanged, CommitType: commitType})
	fd.Close()
	cmd := file.CmdToOpenEditor(tempFilePath)
	if err = cmd.Start(); err != nil {
		// Failed to launch the editor.
		return messageFromCLI()
	} else {
		err = cmd.Wait()
		if err != nil {
			return nil, errors.New("failed to write the commit message")
		}
		return messageFrom(tempFilePath)
	}
}

func messageFromCLI() (*json.CommitMessage, error) {
	reader := bufio.NewReader(os.Stdin)
	fmt.Print("Enter summary: ")
	summary, _ := reader.ReadString('\n')

	if len(summary) == 0 {
		return nil, errors.New("you must input summary")
	}
	fmt.Print("\nEnter detailed comment: ")
	content, _ := reader.ReadString('\n')
	comment := &json.Comment{Content: content, Markup: "PLAINTEXT"}
	commitMessage := &json.CommitMessage{Summary: summary, Detail: comment}
	return commitMessage, nil
}

func messageFrom(filePath string) (*json.CommitMessage, error) {
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
	comment := &json.Comment{Content: getContent(scanner), Markup: "PLAINTEXT"}
	commitMessage := &json.CommitMessage{Summary: summary, Detail: comment}
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

func getContent(scanner *bufio.Scanner) string {
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
	CommitType CommitType
}

var commitMessageTemplate = `
# Please enter the commit message for your changes. Lines starting\n
# with '#' will be ignored, and an empty message aborts the commit.\n
#
# Changes to be committed:
#   {{if eq .CommitType 1}}new file{{else if eq .CommitType 2}}modified{{else}}deleted{{end}}: {{.FilePath}}
#
`
