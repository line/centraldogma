'use strict';

angular.module('CentralDogmaAdmin')
    .factory('ProjectService',
             function (ApiV1Service, StringUtil, EntitiesUtil) {
               return {
                 createProject: function (projectName) {
                   name = StringUtil.requireNotEmpty(projectName, 'projectName');
                   return ApiV1Service.post('projects', {'name': name});
                 },

                 listProjects: function () {
                   return ApiV1Service.get('projects');
                 },

                 listRemovedProjects: function () {
                   return ApiV1Service.get('projects?status=removed');
                 },

                 checkPermission: function (projectName) {
                   return ApiV1Service.get(StringUtil.encodeUri(['projects', projectName]) +
                                           '?checkPermissionOnly=true');
                 },

                 removeProject: function (projectName) {
                   name = StringUtil.requireNotEmpty(projectName, 'projectName');
                   return ApiV1Service.delete(StringUtil.encodeUri(['projects', name]));
                 },

                 restoreProject: function (projectName) {
                   name = StringUtil.requireNotEmpty(projectName, 'projectName');
                   return ApiV1Service.jsonPatch(StringUtil.encodeUri(['projects', name]),
                     EntitiesUtil.toReplaceJsonPatch('/status', 'active'));
                 }
               };
             });
