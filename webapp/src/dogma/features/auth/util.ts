export const WEB_AUTH_LOGIN = '/web/auth/login';

export function setSessionId(id: string) {
  if (typeof window !== 'undefined') {
    localStorage.setItem('sessionId', id);
  }
}

export function getSessionId(): string | null {
  if (typeof window !== 'undefined') {
    return localStorage.getItem('sessionId');
  }
  return null;
}

export function removeSessionId() {
  if (typeof window !== 'undefined') {
    localStorage.removeItem('sessionId');
  }
}
