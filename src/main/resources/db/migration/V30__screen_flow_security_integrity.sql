-- Normalize pre-existing duplicate nicknames before enforcing the same case-insensitive
-- comparison used by the API. Only duplicate rows are changed; the oldest keeps its value.
with ranked_nicknames as (
    select user_id,
           row_number() over (
               partition by lower(btrim(nickname))
               order by created_at, user_id
           ) as duplicate_rank
    from users
)
update users u
set nickname = u.nickname || '_' || left(replace(u.user_id::text, '-', ''), 8)
from ranked_nicknames r
where r.user_id = u.user_id and r.duplicate_rank > 1;

create unique index uq_users_nickname_normalized
    on users (lower(btrim(nickname)));

-- Shared, atomic counters for unauthenticated verification endpoints. Scope keys are
-- one-way hashes, so raw email addresses and client addresses are not persisted.
create table auth_verification_quota (
    action varchar(16) not null,
    scope_hash varchar(64) not null,
    window_kind varchar(8) not null,
    window_start timestamptz not null,
    request_count integer not null,
    primary key (action, scope_hash, window_kind, window_start),
    constraint ck_auth_verification_quota_action check (action in ('send', 'confirm')),
    constraint ck_auth_verification_quota_window check (window_kind in ('hour', 'day')),
    constraint ck_auth_verification_quota_count check (request_count > 0)
);
create index ix_auth_verification_quota_window
    on auth_verification_quota (window_start);
