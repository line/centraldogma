import { UserAndTimestamp } from 'dogma/common/UserAndTimestamp';

export interface AppTokenDto {
  [key: string]: AppTokenDetailDto;
}

export interface AppTokenDetailDto {
  appId: string;
  role: 'MEMBER' | 'OWNER';
  creation: UserAndTimestamp;
}
