'use strict';

angular.module('CentralDogmaAdmin')
    .controller('RepositoryFileNewController',
                function ($scope, $state, $stateParams, $timeout,
                          CentralDogmaConstant, RepositoryService, NotificationUtil, StringUtil) {
                  $scope.project = {
                    name: $stateParams.projectName
                  };
                  $scope.repository = {
                    name: $stateParams.repositoryName
                  };
                  $scope.revision = StringUtil.isEmpty($stateParams.revision) ?
                                    CentralDogmaConstant.HEAD : $stateParams.revision;

                  $scope.path = StringUtil.normalizePath($stateParams.path);
                  $scope.parsedPaths = RepositoryService.parsePath($scope.path);

                  $scope.file = {
                    name: '',
                    type: 'JSON',
                    content: ''
                  };

                  $scope.message = {
                    summary: '',
                    detail: {
                      content: '',
                      markup: 'PLAINTEXT'
                    }
                  };

                  var generateNewPath = function (normalizedPath, newDirectories) {
                    var temp = normalizedPath;
                    if (temp === '/') {
                      temp = '';
                    }

                    return [temp].concat(newDirectories).join('/');
                  };

                  $scope.newDirectories = [];
                  $scope.newPath = generateNewPath($scope.path, $scope.newDirectories);

                  $scope.jsonEditorOptions = {
                    mode: 'code',
                    modes: ['tree', 'code']
                  };

                  $scope.aceLoaded = function (editor) {
                    $scope.editor = editor;
                  };

                  // Auto-focus the file name field.
                  var focusFilenameField = function () {
                    $timeout(function () {
                      angular.element('#fileName').focus();
                    });
                  };

                  focusFilenameField();

                  var filenamePattern = /^[0-9A-Za-z](?:[-+_0-9A-Za-z\.]*[0-9A-Za-z])?$/;

                  $scope.popOnBackspace = false;
                  $scope.keyUp = function (event) {
                    var filename = $scope.file.name;
                    if (StringUtil.isEmpty(filename)) {
                      if (event.keyCode === 8 && // backspace
                          $scope.newDirectories.length > 0) {
                        if ($scope.popOnBackspace) {
                          filename = $scope.newDirectories.pop();
                          $scope.popOnBackspace = false;
                        } else {
                          $scope.popOnBackspace = true;
                        }
                      } else {
                        return;
                      }
                    } else if (filename.indexOf('/') >= 0) { // '/'
                      var filenameArray = filename.split('/');
                      if (filenameArray.length === 0) {
                        $scope.file.name = filename = '';
                      } else {
                        for (var i = 0; i < filenameArray.length - 1; i++) {
                          if (filenameArray[i].match(filenamePattern)) {
                            $scope.newDirectories.push(filenameArray[i]);
                          }
                        }

                        filename = filenameArray[filenameArray.length - 1];
                        $scope.popOnBackspace = StringUtil.isEmpty(filename);
                      }
                    } else {
                      $scope.popOnBackspace = false;
                    }

                    $scope.file.name = filename;

                    if (StringUtil.isEmpty(filename)) {
                      $scope.fileForm.fileName.$pristine = true;
                    } else if (!filename.match(filenamePattern)) {
                      $scope.fileForm.fileName.$invalid = true;
                      return;
                    } else {
                      $scope.fileForm.fileName.$invalid = false;
                    }

                    $scope.newPath = generateNewPath($scope.path, $scope.newDirectories);
                    $scope.file.path = $scope.newPath + '/' + filename;
                  };

                  $scope.createFile = function () {
                    if (!$scope.file.name.match(filenamePattern)) {
                      $scope.fileForm.fileName.$invalid = true;
                      $scope.fileForm.fileName.$pristine = false;
                      NotificationUtil.error('entities.invalid_file_path');
                      focusFilenameField();
                      return;
                    }

                    if (StringUtil.isEmpty($scope.message.summary)) {
                      $scope.message.summary = 'Add ' + $scope.file.path;
                    }

                    if (StringUtil.endsWith($scope.file.name.toLowerCase(), '.json')) {
                      $scope.file.type = 'JSON';
                      try {
                        JSON.parse($scope.file.content);
                      } catch (error) {
                        NotificationUtil.error('entities.invalid_json');
                        $timeout(function () {
                          $scope.editor.focus();
                        });
                        return;
                      }
                    } else if (StringUtil.endsWith($scope.file.name.toLowerCase(), '.json5')) {
                      $scope.file.type = 'JSON';
                    } else {
                      $scope.file.type = 'TEXT';
                    }

                    RepositoryService.addFile($scope.project.name, $scope.repository.name, $scope.revision,
                                              $scope.message, $scope.file).then(
                        function () {
                          NotificationUtil.success('entities.saved_file', { path: $scope.file.path });

                          $scope.back();
                        }, function (error) {
                          NotificationUtil.error(error);
                        });
                  };
                });
