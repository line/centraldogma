'use strict';

angular.module('CentralDogmaAdmin')
    .config(function ($stateProvider, CentralDogmaConstant) {
              $stateProvider
                  .state('repositoryNew', {
                    parent: 'entity',
                    url: '/projects/:projectName/new_repo',
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
                    url: '/projects/:projectName/repos/:repositoryName',
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
                    url: '/projects/:projectName/repos/:repositoryName/list/:revision/{path:repositoryPath}',
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
                    url: '/projects/:projectName/repos/:repositoryName/files/:revision/{path:repositoryPath}',
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
                    url: '/projects/:projectName/repos/:repositoryName/new_file/:revision/{path:repositoryPath}',
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
                    url: '/projects/:projectName/repos/:repositoryName/edit/:revision/{path:repositoryPath}',
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
                    url: '/projects/:projectName/repos/:repositoryName/history/:revision/{path:repositoryPath}',
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
                    url: '/projects/:projectName/repos/:repositoryName/search/:revision?term',
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
                    url: '/projects/:projectName/repos/:repositoryName/query/:revision/{path:repositoryPath}?expression',
                    data: {},
                    views: {
                      'content@': {
                        templateUrl: 'scripts/app/entities/repositories/repository.query.html',
                        controller: 'RepositoryQueryController'
                      }
                    }
                  });
            });
