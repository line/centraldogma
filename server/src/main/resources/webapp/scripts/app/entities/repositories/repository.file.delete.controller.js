'use strict';

angular.module('CentralDogmaAdmin')
    .controller('RepositoryFileDeleteController',
                function ($scope, $uibModalInstance, project, repository, revision, file,
                          RepositoryService, StringUtil, NotificationUtil) {

                  $scope.project = project;
                  $scope.repository = repository;
                  $scope.revision = revision;
                  $scope.file = file;

                  $scope.message = {
                    summary: '',
                    detail: {
                      content: '',
                      markup: 'PLAINTEXT'
                    }
                  };

                  $scope.deleteFile = function () {
                    if (StringUtil.isEmpty($scope.message.summary)) {
                      $scope.message.summary = 'Delete ' + $scope.file.path;
                    }

                    RepositoryService.deleteFile($scope.project.name, $scope.repository.name, $scope.revision,
                                                 $scope.message, $scope.file.path).then(
                        function () {
                          $uibModalInstance.close({
                            translationId: 'entities.deleted_file',
                            interpolateParams: {path: $scope.file.path}
                          });
                        }, function (error) {
                          NotificationUtil.error(error);
                          $uibModalInstance.dismiss(error.message);
                        });
                  };

                  $scope.cancel = function () {
                    $uibModalInstance.dismiss('');
                  };
                });
