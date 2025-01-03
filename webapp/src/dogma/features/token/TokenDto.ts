import { UserAndTimestamp } from 'dogma/common/UserAndTimestamp';

export interface TokenDto {
  appId: string;
  secret?: string;
  systemAdmin: boolean;
  creation: UserAndTimestamp;
  deactivation?: UserAndTimestamp;
}
