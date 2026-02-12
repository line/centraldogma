/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

import { render, screen, waitFor } from '@testing-library/react';
import { ProjectMetadataDto, ProjectCreatorDto } from 'dogma/features/project/ProjectMetadataDto';
import { findUserRole } from 'dogma/features/auth/ProjectRole';
import { findUserRepositoryRole } from 'dogma/features/auth/RepositoryRole';
import { UserDto } from 'dogma/features/auth/UserDto';
import { AppTokenDetailDto } from 'dogma/features/project/settings/tokens/AppTokenDto';
// Disabled due to https://github.com/mswjs/msw/issues/1786
// import { setupServer } from 'msw/node';
// import { http, HttpResponse } from 'msw';
import { apiSlice } from 'dogma/features/api/apiSlice';
import { ApiProvider } from '@reduxjs/toolkit/query/react';
import '@testing-library/jest-dom';

// Helper type for testing - AppTokenDto is Map<string, AppTokenDetailDto> but server returns object
type AppTokenObject = { [key: string]: AppTokenDetailDto };

describe('ProjectMetadataDto', () => {
  let mockMetadata: ProjectMetadataDto;
  let mockUser: UserDto;

  beforeEach(() => {
    mockMetadata = {
      name: 'test-project',
      repos: {
        repo1: {
          name: 'repo1',
          roles: {
            projects: {
              member: 'WRITE',
              guest: 'READ',
            },
            users: {
              'member@example.com': 'WRITE',
            },
            tokens: {
              'test-token': 'READ',
            },
          },
          creation: { user: 'admin@example.com', timestamp: '2024-01-01T00:00:00Z' },
        },
      },
      members: {
        'owner@example.com': {
          login: 'owner@example.com',
          role: 'OWNER',
          creation: { user: 'admin@example.com', timestamp: '2024-01-01T00:00:00Z' },
        },
        'member@example.com': {
          login: 'member@example.com',
          role: 'MEMBER',
          creation: { user: 'admin@example.com', timestamp: '2024-01-01T00:00:00Z' },
        },
      },
      appIds: {
        'app-token-1': {
          appId: 'app-token-1',
          role: 'MEMBER',
          creation: { user: 'admin@example.com', timestamp: '2024-01-01T00:00:00Z' },
        } as AppTokenDetailDto,
        'app-token-2': {
          appId: 'app-token-2',
          role: 'OWNER',
          creation: { user: 'admin@example.com', timestamp: '2024-01-02T00:00:00Z' },
        } as AppTokenDetailDto,
      },
      creation: { user: 'admin@example.com', timestamp: '2024-01-01T00:00:00Z' },
    } as unknown as ProjectMetadataDto;

    mockUser = {
      email: 'owner@example.com',
      systemAdmin: false,
    } as UserDto;
  });

  describe('appIds field', () => {
    it('should have appIds property with token details', () => {
      expect(mockMetadata.appIds).toBeDefined();
      const appIds = mockMetadata.appIds as unknown as AppTokenObject;
      expect(Object.keys(appIds)).toHaveLength(2);
    });

    it('should contain app token with correct structure', () => {
      const appIds = mockMetadata.appIds as unknown as AppTokenObject;
      const appToken = appIds['app-token-1'];
      expect(appToken).toBeDefined();
      expect(appToken.appId).toBe('app-token-1');
      expect(appToken.role).toBe('MEMBER');
      expect(appToken.creation).toBeDefined();
      expect(appToken.creation.user).toBe('admin@example.com');
    });

    it('should allow iteration over appIds values', () => {
      const appIds = mockMetadata.appIds as unknown as AppTokenObject;
      const appTokens = Object.values(appIds);
      expect(appTokens).toHaveLength(2);
      expect(appTokens.map((t) => t.appId)).toContain('app-token-1');
      expect(appTokens.map((t) => t.appId)).toContain('app-token-2');
    });

    it('should support different roles for app tokens', () => {
      const appIds = mockMetadata.appIds as unknown as AppTokenObject;
      const memberToken = appIds['app-token-1'];
      const ownerToken = appIds['app-token-2'];
      expect(memberToken.role).toBe('MEMBER');
      expect(ownerToken.role).toBe('OWNER');
    });
  });

  describe('findUserRole with ProjectMetadataDto', () => {
    it('should return OWNER for system admin users', () => {
      const adminUser: UserDto = {
        email: 'admin@example.com',
        systemAdmin: true,
      } as UserDto;
      const role = findUserRole(adminUser, mockMetadata);
      expect(role).toBe('OWNER');
    });

    it('should return OWNER for project owner', () => {
      const ownerUser: UserDto = {
        email: 'owner@example.com',
        systemAdmin: false,
      } as UserDto;
      const role = findUserRole(ownerUser, mockMetadata);
      expect(role).toBe('OWNER');
    });

    it('should return MEMBER for project member', () => {
      const memberUser: UserDto = {
        email: 'member@example.com',
        systemAdmin: false,
      } as UserDto;
      const role = findUserRole(memberUser, mockMetadata);
      expect(role).toBe('MEMBER');
    });

    it('should return GUEST for unknown users', () => {
      const guestUser: UserDto = {
        email: 'guest@example.com',
        systemAdmin: false,
      } as UserDto;
      const role = findUserRole(guestUser, mockMetadata);
      expect(role).toBe('GUEST');
    });
  });

  describe('findUserRepositoryRole with ProjectMetadataDto', () => {
    it('should return ADMIN for system admin users', () => {
      const adminUser: UserDto = {
        email: 'admin@example.com',
        systemAdmin: true,
      } as UserDto;
      const role = findUserRepositoryRole('repo1', adminUser, mockMetadata);
      expect(role).toBe('ADMIN');
    });

    it('should return ADMIN for project owner', () => {
      const ownerUser: UserDto = {
        email: 'owner@example.com',
        systemAdmin: false,
      } as UserDto;
      const role = findUserRepositoryRole('repo1', ownerUser, mockMetadata);
      expect(role).toBe('ADMIN');
    });

    it('should return WRITE for project member with WRITE role', () => {
      const memberUser: UserDto = {
        email: 'member@example.com',
        systemAdmin: false,
      } as UserDto;
      const role = findUserRepositoryRole('repo1', memberUser, mockMetadata);
      expect(role).toBe('WRITE');
    });

    it('should return null for empty metadata', () => {
      const role = findUserRepositoryRole('repo1', mockUser, {} as ProjectMetadataDto);
      expect(role).toBeNull();
    });

    it('should return null for undefined metadata', () => {
      const role = findUserRepositoryRole('repo1', mockUser, undefined);
      expect(role).toBeNull();
    });
  });

  describe('ProjectMetadataDto structure', () => {
    it('should have all required properties', () => {
      expect(mockMetadata.name).toBeDefined();
      expect(mockMetadata.repos).toBeDefined();
      expect(mockMetadata.members).toBeDefined();
      expect(mockMetadata.appIds).toBeDefined();
      expect(mockMetadata.creation).toBeDefined();
    });

    it('should have valid creation timestamp', () => {
      const creation: ProjectCreatorDto = mockMetadata.creation;
      expect(creation.user).toBe('admin@example.com');
      expect(creation.timestamp).toBe('2024-01-01T00:00:00Z');
    });
  });
});
