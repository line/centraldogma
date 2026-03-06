import { UserAndTimestamp } from 'dogma/common/UserAndTimestamp';

export type AppIdentityType = 'TOKEN' | 'CERTIFICATE';

export interface AppIdentity {
  appId: string;
  type: AppIdentityType;
  systemAdmin: boolean;
  allowGuestAccess: boolean;
  creation: UserAndTimestamp;
  deactivation?: UserAndTimestamp;
  deletion?: UserAndTimestamp;
}

export interface Token extends AppIdentity {
  type: 'TOKEN';
  secret?: string;
}

export interface CertificateAppIdentity extends AppIdentity {
  type: 'CERTIFICATE';
  certificateId: string;
}

export type AppIdentityDto = Token | CertificateAppIdentity;

export const isToken = (identity: AppIdentity): identity is Token => {
  return identity.type === 'TOKEN';
};

export const isCertificate = (identity: AppIdentity): identity is CertificateAppIdentity => {
  return identity.type === 'CERTIFICATE';
};
