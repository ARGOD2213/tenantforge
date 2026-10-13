import { getServerSession } from 'next-auth';
import { redirect } from 'next/navigation';
import { SignInButton } from '@/components/AuthButtons';
import { authOptions } from '@/lib/auth';

export default async function Home() {
  const session = await getServerSession(authOptions);
  if (session?.accessToken && !session.error) redirect('/dashboard');
  return (
    <main className="wrap hero">
      <div className="label">Project 1 · multi-tenant SaaS</div>
      <h1>
        Tenant<span>Forge</span>
      </h1>
      <p className="muted">
        Every organisation gets its own invoices, limits and plan. Sign in as a user of any demo tenant: alice or bob
        (acme), zara (zenith) or omar (orbit). The password is <span className="mono">demo</span>.
      </p>
      <div>
        <SignInButton />
      </div>
    </main>
  );
}
