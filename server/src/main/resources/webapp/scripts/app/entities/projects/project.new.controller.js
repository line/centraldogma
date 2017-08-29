'use strict';

angular.module('CentralDogmaAdmin')
    .controller('ProjectNewController',
                function ($scope, $state, CentralDogmaConstant,
                          ProjectService, NotificationUtil) {

                  $scope.entityNamePattern = CentralDogmaConstant.ENTITY_NAME_PATTERN;

                  $scope.createProject = function () {
                    ProjectService.createProject($scope.project.name).then(
                        function () {
                          NotificationUtil.success('entities.created_project', { name: $scope.project.name });

                          $state.go('project', {
                            projectName: $scope.project.name
                          });
                        }
                    );
                  };
                });
