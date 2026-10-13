'use client';

import { signIn, signOut } from 'next-auth/react';

export function SignInButton() {
  return (
    <button className="btn primary" onClick={() => signIn('keycloak', { callbackUrl: '/dashboard' })}>
      Sign in with Keycloak
    </button>
  );
}

export function SignOutButton() {
  return (
    <button className="btn ghost" onClick={() => signOut({ callbackUrl: '/' })}>
      Sign out
    </button>
  );
}
