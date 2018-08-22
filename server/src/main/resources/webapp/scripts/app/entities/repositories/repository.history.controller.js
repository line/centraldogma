'use strict';

angular.module('CentralDogmaAdmin')
    .controller('RepositoryHistoryController',
                function ($scope, $stateParams, $location,
                          CentralDogmaConstant, RepositoryService, StringUtil) {

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

                  $scope.commits = [];

                  $scope.setRevision = function (revision) {
                    $location.path('/projects/' + $scope.project.name + '/repos/' + $scope.repository.name +
                                   '/history/' + revision + $scope.path);
                  };

                  // TODO(trustin): Pagination
                  RepositoryService.getHistory($scope.project.name, $scope.repository.name, $scope.path,
                                               $scope.revision, 1).then(
                      function (commits) {
                        angular.forEach(commits, function (commit) {
                          if (commit.revision.minor === 0) {
                            commit.revision.revisionNumber = commit.revision.major.toString();
                          }
                          commit.timestampStr = moment(commit.timestamp).fromNow();
                          this.push(commit);
                        }, $scope.commits);
                      }
                  );
                });
