'use server';

import { revalidatePath } from 'next/cache';
import { redirect } from 'next/navigation';
import { api, ApiError } from '@/lib/api';

function fail(e: unknown): never {
  const message =
    e instanceof ApiError
      ? e.status === 429
        ? `Rate limited: try again in ${e.retryAfter ?? 'a few'}s`
        : e.message
      : 'Something went wrong';
  redirect(`/dashboard?error=${encodeURIComponent(message)}`);
}

export async function createInvoice(form: FormData) {
  const amount = Math.round(Number(form.get('amount') ?? 0) * 100);
  try {
    await api('/api/invoices', {
      method: 'POST',
      body: JSON.stringify({
        number: String(form.get('number') ?? '').trim(),
        customer: String(form.get('customer') ?? '').trim(),
        amountCents: Number.isFinite(amount) ? amount : 0,
        currency: String(form.get('currency') ?? 'INR').toUpperCase(),
      }),
    });
  } catch (e) {
    if ((e as { digest?: string }).digest?.startsWith('NEXT_REDIRECT')) throw e;
    fail(e);
  }
  revalidatePath('/dashboard');
}

export async function requestExport() {
  try {
    await api('/api/exports', { method: 'POST' });
  } catch (e) {
    if ((e as { digest?: string }).digest?.startsWith('NEXT_REDIRECT')) throw e;
    fail(e);
  }
  revalidatePath('/dashboard');
}
