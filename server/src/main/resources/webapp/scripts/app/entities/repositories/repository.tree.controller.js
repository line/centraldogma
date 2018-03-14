'use strict';

angular.module('CentralDogmaAdmin')
    .controller('RepositoryTreeController',
                function ($scope, $stateParams, $location, $uibModal, Principal, CentralDogmaConstant, RepositoryService,
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
                  $scope.files = [];
                  $scope.selectedFile = null;

                  $scope.setRevision = function (revision) {
                    $location.path('/' + $scope.project.name + '/' + $scope.repository.name +
                                   '/tree/' + revision + $scope.path);
                  };

                  $scope.selectFile = function (file) {
                    $scope.selectedFile = file === $scope.selectedFile ? null : file;
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
                          return $scope.selectedFile;
                        }
                      }
                    });

                    modalInstance.result.then(
                        function (message) {
                          $scope.selectedFile = null;
                          NotificationUtil.success(message);
                          getTree();
                        });
                  };

                  var getTree = function () {
                    RepositoryService.getTree(
                        $scope.project.name, $scope.repository.name, $scope.revision, $scope.path).then(
                        function (files) {
                          if (angular.isArray(files)) {
                            $scope.files = files;
                            $scope.files.forEach(function (file) {
                              const components = file.path.split('/');
                              file.name = components[components.length - 1];
                            });
                          } else {
                            $scope.files = [];
                          }
                        });
                  };
                  getTree();
                });
