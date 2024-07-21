export interface AppMemberDto {
  [key: string]: AppMemberDetailDto;
}

export interface AppMemberDetailDto {
  login: string;
  role: string;
  creation: { user: string; timestamp: string };
}
