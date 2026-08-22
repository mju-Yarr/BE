-- Screen-flow contracts: pre-signup email code verification, durable onboarding,
-- event route/calendar provenance, calendar sync metadata, and place UX support.

alter table users add column full_name text;
alter table users add column registration_installation_id uuid;

create table email_verification_challenge (
    challenge_id uuid primary key default gen_random_uuid(),
    email text not null,
    code_hash varchar(64) not null,
    ticket_hash varchar(64),
    expires_at timestamptz not null,
    confirmed_at timestamptz,
    consumed_at timestamptz,
    invalidated_at timestamptz,
    failed_attempts smallint not null default 0,
    created_at timestamptz not null default now(),
    constraint ck_email_challenge_attempts check (failed_attempts between 0 and 5)
);
create index ix_email_challenge_active
    on email_verification_challenge (lower(email), created_at desc)
    where consumed_at is null and invalidated_at is null;
create unique index uq_email_challenge_ticket
    on email_verification_challenge (ticket_hash) where ticket_hash is not null;

create table user_onboarding (
    user_id uuid primary key references users(user_id) on delete cascade,
    current_step text not null default 'profile',
    completed_at timestamptz,
    coachmark_seen_at timestamptz,
    updated_at timestamptz not null default now()
);
insert into user_onboarding (user_id, current_step, completed_at)
select user_id, case when exists (select 1 from user_setting s where s.user_id = u.user_id)
                    then 'completed' else 'profile' end,
       case when exists (select 1 from user_setting s where s.user_id = u.user_id)
            then now() else null end
from users u
on conflict (user_id) do nothing;

alter table event add column anchor_mode text not null default 'arrive_by';
alter table event add column destination_address text;
alter table event add constraint ck_event_anchor_mode check (anchor_mode in ('arrive_by', 'depart_at'));

alter table calendar_connection add column last_synced_at timestamptz;

alter table bookmark add column address text;
alter table bookmark add column sort_order integer not null default 0;
alter table bookmark add column updated_at timestamptz not null default now();
create index ix_bookmark_user_order on bookmark (user_id, sort_order, created_at desc);

create table recent_destination (
    recent_destination_id uuid primary key default gen_random_uuid(),
    user_id uuid not null references users(user_id) on delete cascade,
    place_name text not null,
    address text,
    lat decimal(9,6) not null,
    lng decimal(10,6) not null,
    use_count integer not null default 1,
    last_used_at timestamptz not null default now(),
    constraint ck_recent_destination_lat check (lat between -90 and 90),
    constraint ck_recent_destination_lng check (lng between -180 and 180),
    constraint uq_recent_destination_coordinates unique (user_id, lat, lng)
);
create index ix_recent_destination_user_time on recent_destination (user_id, last_used_at desc);

alter table route_search_option add column legs jsonb not null default '[]'::jsonb;
alter table route_search_option add column degraded jsonb not null default '[]'::jsonb;

alter table route_option add column provider text not null default 'unknown';
alter table route_option add column legs jsonb not null default '[]'::jsonb;
alter table route_option add column degraded jsonb not null default '[]'::jsonb;
alter table route_option add column raw_ref text;
