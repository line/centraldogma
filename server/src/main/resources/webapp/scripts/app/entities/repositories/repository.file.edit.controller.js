'use strict';

angular.module('CentralDogmaAdmin')
    .controller('RepositoryFileEditController',
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

                  $scope.file = null;
                  $scope.absRevision = null;

                  $scope.message = {
                    summary: '',
                    detail: {
                      content: '',
                      markup: 'PLAINTEXT'
                    }
                  };

                  $scope.aceLoaded = function (editor) {
                    $timeout(function() { editor.focus(); });
                    $scope.fetchAndMerge();
                  };

                  var getDiffs = function (type, origContent, curContent) {
                    switch (type) {
                    case 'JSON':
                      return jsonpatch.compare(JSON.parse(origContent), JSON.parse(curContent));
                    case 'TEXT':
                      return JsDiff.structuredPatch(null, null, origContent, curContent, null, null);
                    default:
                      throw new Error(JSON.stringify({
                        translationId: 'entities.unsupported_file_type',
                        interpolateParams: { 'type': $scope.file.type }
                      }));
                    }
                  };

                  var applyJsonPatch = function (content, diffs) {
                    if (diffs.length == 0) {
                      return false;
                    }

                    var contentJson = JSON.parse(content);
                    if (jsonpatch.apply(contentJson, diffs, true) === false) {
                      throw new Error('entities.auto_merge_failed');
                    }

                    return JSON.stringify(contentJson, null, 2) + '\n';
                  };

                  var applyTextPatch = function (content, diffs) {
                    if (diffs.hunks.length == 0) {
                      return false;
                    }

                    var textPatchApplied = JsDiff.applyPatch(content, diffs);
                    if (textPatchApplied === false) {
                      throw new Error('entities.auto_merge_failed');
                    }

                    return textPatchApplied;
                  };

                  var applyDiffs = function (type, content, diffs) {
                    if (diffs == null) {
                      return false;
                    }

                    switch (type) {
                    case 'JSON':
                      return applyJsonPatch(content, diffs);
                    case 'TEXT':
                      return applyTextPatch(content, diffs);
                    default:
                      throw new Error(JSON.stringify({
                        translationId: 'entities.unsupported_file_type',
                        interpolateParams: { 'type': type }
                      }));
                    }
                  };

                  $scope.editFile = function () {
                    $scope.message.summary = StringUtil.defaultString($scope.message.summary,
                                                                      'Edit ' + $scope.file.path);

                    RepositoryService.editFile($scope.project.name, $scope.repository.name, $scope.absRevision,
                                               $scope.message, $scope.file).then(
                        function () {
                          NotificationUtil.success('entities.saved_file', { path: $scope.path });

                          $scope.back();
                        }, function (error) {
                          switch (error.status) {
                          case 409:
                            NotificationUtil.error('entities.conflict_occurred');
                            break;
                          default:
                            NotificationUtil.error(error);
                          }
                        });
                  };

                  $scope.fetchAndMerge = function () {
                    try {
                      var diffs = null;
                      if ($scope.file != null) {
                        diffs = getDiffs($scope.file.type, $scope.origFileContent, $scope.file.content);
                      }

                      RepositoryService.getFile($scope.project.name, $scope.repository.name, $scope.revision,
                          {path: $scope.path}).then(
                          function (fileWithRevision) {
                            $scope.absRevision = fileWithRevision.revision;
                            $scope.file = fileWithRevision.file;
                            if ($scope.file.type === 'JSON') {
                              $scope.file.content =
                                  JSON.stringify(JSON.parse($scope.file.content), null, 2) + '\n';
                            }
                            $scope.origFileContent = $scope.file.content;

                            var diffsApplied = applyDiffs($scope.file.type, $scope.file.content, diffs);
                            if (diffsApplied === false) {
                              $scope.isDiffsApplied = false;
                            } else {
                              $scope.isDiffsApplied = true;
                              $scope.file.content = diffsApplied;
                            }
                          });
                    } catch (error) {
                      var message = error.message;
                      try {
                        message = JSON.parse(message);
                      } catch (ignored) {}
                      NotificationUtil.error(message);
                    }
                  };
                });
