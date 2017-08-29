'use strict';

angular.module('CentralDogmaAdmin')
    .controller('ProjectsController',
                function ($scope, ProjectService) {
                  ProjectService.listProjects().then(
                      function (projects) {
                        $scope.projects = projects;
                      }
                  );
                });
