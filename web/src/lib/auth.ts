import type { NextAuthOptions } from 'next-auth';
import type { JWT } from 'next-auth/jwt';

/**
 * Keycloak login (authorization code + PKCE). The browser talks to Keycloak's public URL; the Next.js server talks
 * to it over the internal network. Keycloak is configured (KC_HOSTNAME) to issue tokens with the public URL as
 * issuer, so both sides agree on `iss`.
 */
const issuer = process.env.KEYCLOAK_ISSUER ?? 'http://localhost:8080/realms/tenantforge';
const internal = process.env.KEYCLOAK_INTERNAL_URL ?? issuer;
const clientId = process.env.KEYCLOAK_CLIENT_ID ?? 'tenantforge-web';
const clientSecret = process.env.KEYCLOAK_CLIENT_SECRET ?? '';

type KeycloakProfile = { sub: string; name?: string; preferred_username?: string; email?: string };

async function refresh(token: JWT): Promise<JWT> {
  try {
    const res = await fetch(`${internal}/protocol/openid-connect/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'refresh_token',
        refresh_token: String(token.refreshToken ?? ''),
        client_id: clientId,
        client_secret: clientSecret,
      }),
      cache: 'no-store',
    });
    const body = await res.json();
    if (!res.ok) throw new Error(body.error ?? 'refresh failed');
    return {
      ...token,
      accessToken: body.access_token,
      refreshToken: body.refresh_token ?? token.refreshToken,
      expiresAt: Math.floor(Date.now() / 1000) + Number(body.expires_in ?? 300),
      error: undefined,
    };
  } catch {
    return { ...token, error: 'RefreshFailed' };
  }
}

export const authOptions: NextAuthOptions = {
  providers: [
    {
      id: 'keycloak',
      name: 'Keycloak',
      type: 'oauth',
      clientId,
      clientSecret,
      issuer,
      authorization: { url: `${issuer}/protocol/openid-connect/auth`, params: { scope: 'openid profile email' } },
      token: `${internal}/protocol/openid-connect/token`,
      userinfo: `${internal}/protocol/openid-connect/userinfo`,
      jwks_endpoint: `${internal}/protocol/openid-connect/certs`,
      idToken: true,
      checks: ['pkce', 'state'],
      profile(profile: KeycloakProfile) {
        return { id: profile.sub, name: profile.name ?? profile.preferred_username, email: profile.email };
      },
    },
  ],
  session: { strategy: 'jwt' },
  pages: { signIn: '/' },
  callbacks: {
    async jwt({ token, account }) {
      if (account) {
        return {
          ...token,
          accessToken: account.access_token,
          refreshToken: account.refresh_token,
          expiresAt: account.expires_at,
        };
      }
      if (typeof token.expiresAt === 'number' && Date.now() < (token.expiresAt - 30) * 1000) {
        return token;
      }
      return refresh(token);
    },
    async session({ session, token }) {
      session.accessToken = token.accessToken as string | undefined;
      session.error = token.error as string | undefined;
      return session;
    },
  },
};
