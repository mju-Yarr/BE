-- 홈 wrap 카드(S-06)의 "정시 도착" 칸. 주간 요약과 같은 정의를 쓴다.
-- on_time만 정시로 세고, early는 표본에 들어가되 정시는 아니며, unknown은 표본에서도 빠진다.
--
-- 표본 수를 함께 저장하는 이유: on_time_count가 0일 때 "정시가 한 번도 없었다"와
-- "도착 결과를 모른다"를 구분해야 한다. 클라이언트는 표본이 0이면 칸을 감춘다.
alter table daily_wellness_summary
    add column on_time_count integer not null default 0,
    add column arrival_sample_count integer not null default 0;

alter table daily_wellness_summary
    add constraint ck_summary_arrival_counts
        check (
            on_time_count >= 0
            and arrival_sample_count >= 0
            and on_time_count <= arrival_sample_count
        );
