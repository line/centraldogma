'use strict';

angular.module('CentralDogmaAdmin')
    .factory('ProjectService',
             function (ApiService, StringUtil) {
               return {
                 createProject: function (projectName) {
                   projectName = StringUtil.requireNotEmpty(projectName, 'projectName');

                   return ApiService.post('projects', {'name': projectName});
                 },

                 listProjects: function () {
                   return ApiService.get('projects');
                 }
               };
             });
