'use strict';

angular.module('CentralDogmaAdmin')
    .config(function ($stateProvider, CentralDogmaConstant) {
              $stateProvider
                  .state('repositoryNew', {
                    parent: 'entity',
                    url: '/:projectName/new',
                    data: {},
                    views: {
                      'content@': {
                        templateUrl: 'scripts/app/entities/repositories/repository.new.html',
                        controller: 'RepositoryNewController'
                      }
                    }
                  })
                  .state('repository', {
                    parent: 'entity',
                    url: '/:projectName/:repositoryName',
                    data: {},
                    views: {
                      'content@': {
                        templateUrl: 'scripts/app/entities/repositories/repository.tree.html',
                        controller: 'RepositoryTreeController'
                      }
                    }
                  })
                  .state('repositoryTree', {
                    parent: 'entity',
                    url: '/:projectName/:repositoryName/tree/:revision/{path:repositoryPath}',
                    data: {},
                    views: {
                      'content@': {
                        templateUrl: 'scripts/app/entities/repositories/repository.tree.html',
                        controller: 'RepositoryTreeController'
                      }
                    }
                  })
                  .state('repositoryFile', {
                    parent: 'entity',
                    url: '/:projectName/:repositoryName/files/:revision/{path:repositoryPath}',
                    data: {},
                    views: {
                      'content@': {
                        templateUrl: 'scripts/app/entities/repositories/repository.file.html',
                        controller: 'RepositoryFileController'
                      }
                    }
                  })
                  .state('repositoryFileNew', {
                    parent: 'entity',
                    url: '/:projectName/:repositoryName/new/:revision/{path:repositoryPath}',
                    data: {
                      roles: [CentralDogmaConstant.LEVEL_USER]
                    },
                    views: {
                      'content@': {
                        templateUrl: 'scripts/app/entities/repositories/repository.file.new.html',
                        controller: 'RepositoryFileNewController'
                      }
                    }

                  })
                  .state('repositoryFileEdit', {
                    parent: 'entity',
                    url: '/:projectName/:repositoryName/edit/:revision/{path:repositoryPath}',
                    data: {
                      roles: [CentralDogmaConstant.LEVEL_USER]
                    },
                    views: {
                      'content@': {
                        templateUrl: 'scripts/app/entities/repositories/repository.file.edit.html',
                        controller: 'RepositoryFileEditController'
                      }
                    }

                  })
                  .state('repositoryHistory', {
                    parent: 'entity',
                    url: '/:projectName/:repositoryName/history/:revision/{path:repositoryPath}',
                    data: {},
                    views: {
                      'content@': {
                        templateUrl: 'scripts/app/entities/repositories/repository.history.html',
                        controller: 'RepositoryHistoryController'
                      }
                    }
                  })
                  .state('repositorySearch', {
                    parent: 'entity',
                    url: '/:projectName/:repositoryName/search/:revision?term',
                    data: {},
                    views: {
                      'content@': {
                        templateUrl: 'scripts/app/entities/repositories/repository.search.html',
                        controller: 'RepositorySearchController'
                      }
                    }
                  })
                  .state('repositoryQuery', {
                    parent: 'entity',
                    url: '/:projectName/:repositoryName/query/:revision/{path:repositoryPath}?expression',
                    data: {},
                    views: {
                      'content@': {
                        templateUrl: 'scripts/app/entities/repositories/repository.query.html',
                        controller: 'RepositoryQueryController'
                      }
                    }
                  });
            });
