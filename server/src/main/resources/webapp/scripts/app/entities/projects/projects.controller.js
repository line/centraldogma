'use strict';

angular.module('CentralDogmaAdmin')
    .controller('ProjectsController',
                function ($scope, ProjectService, $location, NotificationUtil, ConfirmationDialog) {
                  $scope.movePageIfAccessible = function (projectName) {
                    ProjectService.checkPermission(projectName).then(function () {
                      $location.url('/metadata/' + projectName);
                    }, function (error) {
                      NotificationUtil.error(error);
                    })
                  };

                  $scope.restoreProject = function (projectName) {
                    ConfirmationDialog.openModal('entities.title_restore_project', {
                      target: projectName
                    }).then(function () {
                      ProjectService.restoreProject(projectName).then(function () {
                        NotificationUtil.success('entities.title_project_restored', {
                          target: projectName
                        });
                        $scope.refresh();
                      });
                    });
                  };

                  $scope.refresh = function () {
                    ProjectService.listProjects().then(
                      function (projects) {
                        $scope.projects = projects;
                      }
                    );
                    ProjectService.listRemovedProjects().then(
                      function (removedProjects) {
                        $scope.removedProjects = removedProjects;
                      }
                    )
                  };

                  $scope.refresh();
                });
