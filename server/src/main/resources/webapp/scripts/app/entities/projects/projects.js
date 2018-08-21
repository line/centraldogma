'use strict';

angular.module('CentralDogmaAdmin')
    .config(function ($stateProvider, CentralDogmaConstant) {
              $stateProvider
                  .state('projects', {
                    parent: 'entity',
                    url: '/projects',
                    data: {},
                    views: {
                      'content@': {
                        templateUrl: 'scripts/app/entities/projects/projects.html',
                        controller: 'ProjectsController'
                      }
                    }
                  })
                  .state('projectNew', {
                    parent: 'entity',
                    url: '/new_project',
                    data: {
                      roles: [CentralDogmaConstant.LEVEL_USER]
                    },
                    views: {
                      'content@': {
                        templateUrl: 'scripts/app/entities/projects/project.new.html',
                        controller: 'ProjectNewController'
                      }
                    }
                  })
                  .state('project', {
                    parent: 'entity',
                    url: '/projects/:projectName',
                    data: {},
                    views: {
                      'content@': {
                        templateUrl: 'scripts/app/entities/projects/project.html',
                        controller: 'ProjectController'
                      }
                    }
                  });
            });
