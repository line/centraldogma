'use strict';

angular.module('CentralDogmaAdmin')
    .controller('RepositoryFileController',
                function ($scope, $state, $stateParams, $timeout, $location, $uibModal,
                          CentralDogmaConstant, RepositoryService,
                          NotificationUtil, StringUtil) {

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

                  $scope.aceLoaded = function (editor) {
                    $timeout(function() { editor.focus(); });
                  };

                  $scope.setRevision = function (revision) {
                    $location.path('/projects/' + $scope.project.name + '/repos/' + $scope.repository.name +
                                   '/files/' + revision + $scope.path);
                  };

                  $scope.deleteFile = function () {
                    var modalInstance = $uibModal.open({
                      templateUrl: 'scripts/app/entities/repositories/repository.file.delete.html',
                      controller: 'RepositoryFileDeleteController',
                      resolve: {
                        project: function () {
                          return $scope.project;
                        },
                        repository: function () {
                          return $scope.repository;
                        },
                        revision: function () {
                          return $scope.revision;
                        },
                        file: function () {
                          return $scope.file;
                        }
                      }
                    });

                    modalInstance.result.then(
                        function (message) {
                          NotificationUtil.success(message);
                          $scope.back();
                        });
                  };

                  RepositoryService.getFile($scope.project.name, $scope.repository.name, $scope.revision,
                                            {path: $scope.path}).then(
                      function (file) {
                        if (file.type === 'JSON') {
                          file.content = JSON.stringify(JSON.parse(file.content), null, 2) + '\n';
                        }
                        $scope.file = file;
                      }
                  );
                });
