import { useState } from 'react';
import type { SearchResult } from '../types';

interface Props {
  /** Run a cross-session search (GET /search?q=). */
  onSearch: (term: string) => void;
  /** Latest results for the active term, or undefined before any search. */
  results?: SearchResult[];
  isSearching?: boolean;
  /** The term the current `results` belong to. */
  activeTerm?: string;
  /** Open the session a result belongs to. */
  onOpenSession: (sessionId: string) => void;
}

function formatTime(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

/**
 * A top-level cross-session transcript search. Submitting runs `search(q)`; a
 * slow search never blocks live control (results seed asynchronously).
 */
export function SearchBox({
  onSearch,
  results,
  isSearching,
  activeTerm,
  onOpenSession,
}: Props) {
  const [term, setTerm] = useState('');
  const canSearch = term.trim() !== '';

  const submit = () => {
    if (!canSearch) return;
    onSearch(term.trim());
  };

  return (
    <section aria-labelledby="search-heading" className="search">
      <form
        className="search__form"
        role="search"
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
      >
        <h2 id="search-heading" className="search__label">
          Search transcripts
        </h2>
        <div className="search__row">
          <input
            data-testid="search-input"
            className="search__input"
            type="search"
            value={term}
            placeholder="Search across all sessions…"
            onChange={(e) => setTerm(e.target.value)}
          />
          <button
            type="submit"
            className="btn btn--sm btn--primary search__btn"
            data-testid="search-submit"
            disabled={!canSearch || isSearching}
          >
            {isSearching ? 'Searching…' : 'Search'}
          </button>
        </div>
      </form>

      {results && (
        <div className="search__results" data-testid="search-results">
          {results.length === 0 ? (
            <p className="empty empty--sm">
              No matches{activeTerm ? ` for “${activeTerm}”` : ''}.
            </p>
          ) : (
            <ul className="search-list">
              {results.map((r, i) => (
                <li
                  className="search-item"
                  data-testid="search-item"
                  key={`${r.sessionId}-${r.at}-${i}`}
                >
                  <button
                    type="button"
                    className="search-item__btn"
                    onClick={() => onOpenSession(r.sessionId)}
                    title="Open this session"
                  >
                    <span className="search-item__head">
                      <span className="search-item__machine">{r.machineName}</span>
                      <span className="search-item__role">{r.role}</span>
                      <time className="search-item__at" dateTime={r.at}>
                        {formatTime(r.at)}
                      </time>
                    </span>
                    <span className="search-item__snippet">{r.snippet}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </section>
  );
}
