import { UserAndTimestamp } from 'dogma/common/UserAndTimestamp';

export interface AppIdDto {
  [key: string]: AppIdDetailDto;
}

export interface AppIdDetailDto {
  appId: string;
  role: 'MEMBER' | 'OWNER';
  creation: UserAndTimestamp;
}
