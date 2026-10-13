import { getServerSession } from 'next-auth';
import { redirect } from 'next/navigation';
import { authOptions } from './auth';

const API_URL = process.env.API_URL ?? 'http://localhost:8081';

export type Me = {
  user: string;
  roles: string[];
  tenant: { id: string; slug: string; name: string; plan: string; tier: 'POOLED' | 'SILO' };
  limits: { requestsPerSecond: number; burst: number };
  entitlements: { exports: boolean };
};

export type Invoice = {
  id: string;
  number: string;
  customer: string;
  amountCents: number;
  currency: string;
  status: string;
  issuedOn: string;
  dueOn: string | null;
};

export type ExportJob = {
  id: string;
  status: 'queued' | 'running' | 'done' | 'failed';
  rows: number | null;
  error: string | null;
  createdAt: string;
  downloadUrl: string | null;
};

export class ApiError extends Error {
  constructor(public status: number, public body: { error?: string; detail?: string }, public retryAfter?: string) {
    super(body.detail ?? body.error ?? `API error ${status}`);
  }
}

/** Server-side call to the TenantForge API with the signed-in user's access token. */
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken || session.error) redirect('/');
  const res = await fetch(`${API_URL}${path}`, {
    ...init,
    headers: { ...init.headers, Authorization: `Bearer ${session.accessToken}`, 'Content-Type': 'application/json' },
    cache: 'no-store',
  });
  if (res.status === 401) redirect('/');
  const text = await res.text();
  const body = text ? JSON.parse(text) : {};
  if (!res.ok) throw new ApiError(res.status, body, res.headers.get('Retry-After') ?? undefined);
  return body as T;
}

export function money(cents: number, currency: string) {
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency }).format(cents / 100);
}
