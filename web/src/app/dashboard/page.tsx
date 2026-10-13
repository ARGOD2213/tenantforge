import { SignOutButton } from '@/components/AuthButtons';
import { api, ApiError, money, type ExportJob, type Invoice, type Me } from '@/lib/api';
import { createInvoice, requestExport } from './actions';

export const dynamic = 'force-dynamic';

export default async function Dashboard({ searchParams }: { searchParams: Promise<{ error?: string }> }) {
  const { error } = await searchParams;
  let me: Me;
  let invoices: Invoice[];
  let exports: ExportJob[];
  try {
    [me, invoices, exports] = await Promise.all([
      api<Me>('/api/me'),
      api<Invoice[]>('/api/invoices'),
      api<ExportJob[]>('/api/exports'),
    ]);
  } catch (e) {
    if (e instanceof ApiError) {
      return (
        <main className="wrap">
          <div className={`notice ${e.status === 429 ? 'warn' : 'bad'}`}>
            {e.status === 429 ? `Your tenant hit its rate limit. Retry in ${e.retryAfter ?? 'a few'}s.` : e.message}
          </div>
          <SignOutButton />
        </main>
      );
    }
    throw e;
  }

  const isAdmin = me.roles.includes('tenant_admin');
  const total = invoices.reduce((sum, i) => sum + i.amountCents, 0);

  return (
    <main className="wrap">
      <header className="top">
        <div>
          <div className="brand">
            Tenant<span>Forge</span> · {me.tenant.name}
          </div>
          <div className="muted" style={{ fontSize: 13 }}>
            {me.user} · {me.roles.join(', ')}
          </div>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          <span className="pill plan">{me.tenant.plan} plan</span>
          <span className={`pill ${me.tenant.tier === 'SILO' ? 'silo' : ''}`}>
            {me.tenant.tier === 'SILO' ? 'dedicated database' : 'pooled database'}
          </span>
          <SignOutButton />
        </div>
      </header>

      {error && <div className="notice bad">{error}</div>}

      <section className="grid">
        <div className="card">
          <div className="label">Invoices</div>
          <div className="stat">{invoices.length}</div>
        </div>
        <div className="card">
          <div className="label">Billed (all currencies)</div>
          <div className="stat">{(total / 100).toLocaleString('en-IN')}</div>
        </div>
        <div className="card">
          <div className="label">API limit</div>
          <div className="stat">{me.limits.requestsPerSecond}/s</div>
          <div className="faint" style={{ fontSize: 12 }}>burst {me.limits.burst}, per tenant</div>
        </div>
        <div className="card">
          <div className="label">Isolation</div>
          <div style={{ fontSize: 13 }} className="muted">
            Row-Level Security in Postgres. Queries carry no tenant filter, so they can&apos;t forget one.
          </div>
        </div>
      </section>

      <section className="card" style={{ marginBottom: 20 }}>
        <h2>Invoices</h2>
        <div className="tablewrap">
          <table>
            <thead>
              <tr>
                <th>Number</th>
                <th>Customer</th>
                <th>Status</th>
                <th>Issued</th>
                <th style={{ textAlign: 'right' }}>Amount</th>
              </tr>
            </thead>
            <tbody>
              {invoices.length === 0 && (
                <tr>
                  <td colSpan={5} className="faint">
                    No invoices yet. Add the first one below.
                  </td>
                </tr>
              )}
              {invoices.map((i) => (
                <tr key={i.id}>
                  <td className="mono">{i.number}</td>
                  <td>{i.customer}</td>
                  <td>{i.status}</td>
                  <td>{i.issuedOn}</td>
                  <td className="num">{money(i.amountCents, i.currency)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <form action={createInvoice} className="inline">
          <input name="number" placeholder="Number (e.g. INV-104)" required maxLength={40} aria-label="Invoice number" />
          <input name="customer" placeholder="Customer" required maxLength={120} aria-label="Customer" />
          <input name="amount" type="number" min="0" step="0.01" placeholder="Amount" required aria-label="Amount" />
          <input name="currency" defaultValue="INR" maxLength={3} size={4} aria-label="Currency" />
          <button className="btn primary" type="submit">
            Add invoice
          </button>
        </form>
      </section>

      <section className="card">
        <h2>Exports</h2>
        <p className="muted" style={{ marginTop: 0, fontSize: 14 }}>
          Exports run on a background worker (SQS) and land in your tenant&apos;s own folder in S3.
        </p>
        <form action={requestExport}>
          <button className="btn" type="submit" disabled={!isAdmin || !me.entitlements.exports}>
            Export invoices to CSV
          </button>
          {!me.entitlements.exports && <span className="faint"> Not included in the {me.tenant.plan} plan.</span>}
          {me.entitlements.exports && !isAdmin && <span className="faint"> Admins only.</span>}
        </form>
        {exports.length > 0 && (
          <div className="tablewrap" style={{ marginTop: 12 }}>
            <table>
              <thead>
                <tr>
                  <th>Requested</th>
                  <th>Status</th>
                  <th>Rows</th>
                  <th>File</th>
                </tr>
              </thead>
              <tbody>
                {exports.map((x) => (
                  <tr key={x.id}>
                    <td>{new Date(x.createdAt).toLocaleString('en-IN')}</td>
                    <td className={`status-${x.status}`}>{x.status}</td>
                    <td>{x.rows ?? '—'}</td>
                    <td>{x.downloadUrl ? <a href={x.downloadUrl}>Download</a> : x.error ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </main>
  );
}
