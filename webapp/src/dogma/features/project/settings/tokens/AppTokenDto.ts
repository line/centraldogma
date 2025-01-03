import { UserAndTimestamp } from 'dogma/common/UserAndTimestamp';

export type AppTokenDto = Map<string, AppTokenDetailDto>;
export interface AppTokenDetailDto {
  appId: string;
  role: 'MEMBER' | 'OWNER';
  creation: UserAndTimestamp;
}
