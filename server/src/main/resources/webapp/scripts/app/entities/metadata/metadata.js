'use strict';

angular.module('CentralDogmaAdmin')
    .config(function ($stateProvider, CentralDogmaConstant) {
              $stateProvider
                  .state('projectMetadata', {
                    parent: 'entity',
                    url: '/metadata/:projectName',
                    data: {},
                    views: {
                      'content@': {
                        templateUrl: 'scripts/app/entities/metadata/metadata.project.html',
                        controller: 'MetadataProjectController'
                      }
                    }
                  }).state('repositoryMetadata', {
                    parent: 'entity',
                    url: '/metadata/:projectName/:repoName',
                    data: {},
                    views: {
                      'content@': {
                        templateUrl: 'scripts/app/entities/metadata/metadata.repository.html',
                        controller: 'MetadataRepositoryController'
                      }
                    }
                  });
            });
