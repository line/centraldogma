import { UserAndTimestamp } from 'dogma/common/UserAndTimestamp';

export type AppIdDto = Map<string, AppIdDetailDto>;
export interface AppIdDetailDto {
  appId: string;
  role: 'MEMBER' | 'OWNER';
  creation: UserAndTimestamp;
}
