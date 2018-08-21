'use strict';

angular.module('CentralDogmaAdmin')
    .controller('RepositorySearchController',
                function ($scope, $state, $stateParams, $location, $window,
                          CentralDogmaConstant, RepositoryService, StringUtil) {
                  $scope.project = {
                    name: $stateParams.projectName
                  };
                  $scope.repository = {
                    name: $stateParams.repositoryName
                  };
                  $scope.revision = StringUtil.isEmpty($stateParams.revision) ?
                                    CentralDogmaConstant.HEAD : $stateParams.revision;

                  $scope.term = !angular.isString($stateParams.term) ? '' : $stateParams.term;
                  if ($scope.term === 'true') {
                    $scope.term = '';
                  }

                  $scope.files = [];

                  $scope.setRevision = function (revision) {
                    console.log($scope.term);
                    $location.path('/projects/' + $scope.project.name + '/repos/' + $scope.repository.name +
                                   '/search/' + revision);
                  };

                  $scope.search = function () {
                    $state.go('repositorySearch', {
                      projectName: $scope.project.name,
                      repositoryName: $scope.repository.name,
                      revision: $scope.revision,
                      term: $scope.term
                    });
                  };

                  if (StringUtil.isNotEmpty($scope.term)) {
                    RepositoryService.search($scope.project.name, $scope.repository.name, $scope.revision,
                                             $scope.term).then(
                        function (files) {
                          $scope.files = files;
                        }
                    );
                  }
                });
