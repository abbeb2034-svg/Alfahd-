-- ============================================================================
-- مشروع سجل الفهد - المخطط الكامل لقاعدة البيانات في Supabase (PostgreSQL)
-- الملف: 001_sijil_alfahd_schema.sql
-- الوصف: إنشاء جميع الجداول (12 جدولاً) مع القيود والمفاتيح والفهارس والدوال
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

-- دالة مساعدة لتحديث حقل updated_at وزيادة sync_version تلقائياً
CREATE OR REPLACE FUNCTION public.handle_updated_at_and_version()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = NOW();
    IF TG_OP = 'UPDATE' AND (OLD IS DISTINCT FROM NEW) THEN
        IF to_jsonb(NEW) ? 'sync_version' THEN
            NEW.sync_version = COALESCE(OLD.sync_version, 1) + 1;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- ============================================================================
-- 1. جدول الملفات الشخصية والمستخدمين (profiles)
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.profiles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    auth_user_id    UUID UNIQUE REFERENCES auth.users(id) ON DELETE SET NULL,
    username        TEXT NOT NULL UNIQUE,
    full_name       TEXT NOT NULL,
    phone           TEXT,
    role            TEXT NOT NULL DEFAULT 'employee'
                    CHECK (role IN ('admin', 'employee', 'aqil', 'representative')),
    permissions     JSONB NOT NULL DEFAULT '{
        "can_add_citizen": true,
        "can_edit_citizen": false,
        "can_delete_citizen": false,
        "can_view_reports": true,
        "can_export": true,
        "can_manage_aid": false,
        "can_manage_areas": false,
        "can_manage_users": false,
        "can_backup_restore": false
    }'::jsonb,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at   TIMESTAMPTZ,
    created_by      UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sync_version    BIGINT NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_profiles_role ON public.profiles(role);
CREATE INDEX IF NOT EXISTS idx_profiles_active ON public.profiles(is_active);
CREATE INDEX IF NOT EXISTS idx_profiles_username_trgm ON public.profiles USING GIN (username gin_trgm_ops);

CREATE TRIGGER trg_profiles_updated_at
    BEFORE UPDATE ON public.profiles
    FOR EACH ROW EXECUTE FUNCTION public.handle_updated_at_and_version();

-- ============================================================================
-- 2. جدول الحارات (neighborhoods)
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.neighborhoods (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL,
    governorate     TEXT NOT NULL DEFAULT '',
    district        TEXT NOT NULL DEFAULT '',
    aqil_profile_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    aqil_name       TEXT NOT NULL DEFAULT '',
    aqil_phone      TEXT NOT NULL DEFAULT '',
    notes           TEXT NOT NULL DEFAULT '',
    created_by      UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    created_by_name TEXT NOT NULL DEFAULT '',
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sync_version    BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT uq_neighborhood_name_district UNIQUE (name, governorate, district)
);

CREATE INDEX IF NOT EXISTS idx_neighborhoods_aqil ON public.neighborhoods(aqil_profile_id);
CREATE INDEX IF NOT EXISTS idx_neighborhoods_deleted ON public.neighborhoods(is_deleted);

CREATE TRIGGER trg_neighborhoods_updated_at
    BEFORE UPDATE ON public.neighborhoods
    FOR EACH ROW EXECUTE FUNCTION public.handle_updated_at_and_version();

-- ============================================================================
-- 3. جدول المربعات السكنية (residential_squares)
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.residential_squares (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    neighborhood_id             UUID NOT NULL REFERENCES public.neighborhoods(id) ON DELETE CASCADE,
    name                        TEXT NOT NULL,
    square_code                 TEXT NOT NULL DEFAULT '',
    representative_profile_id   UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    representative_name         TEXT NOT NULL DEFAULT '',
    representative_phone        TEXT NOT NULL DEFAULT '',
    notes                       TEXT NOT NULL DEFAULT '',
    created_by                  UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    created_by_name             TEXT NOT NULL DEFAULT '',
    is_deleted                  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sync_version                BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT uq_square_per_neighborhood UNIQUE (neighborhood_id, name)
);

CREATE INDEX IF NOT EXISTS idx_squares_neighborhood ON public.residential_squares(neighborhood_id);
CREATE INDEX IF NOT EXISTS idx_squares_rep ON public.residential_squares(representative_profile_id);
CREATE INDEX IF NOT EXISTS idx_squares_deleted ON public.residential_squares(is_deleted);

CREATE TRIGGER trg_squares_updated_at
    BEFORE UPDATE ON public.residential_squares
    FOR EACH ROW EXECUTE FUNCTION public.handle_updated_at_and_version();

-- ============================================================================
-- 4. جدول سجل تعيينات العقال والمندوبين (representatives)
-- يحفظ التاريخ الكامل لتغيير المندوب أو العاقل دون فقدان البيانات السابقة
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.representatives (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id      UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    role_type       TEXT NOT NULL CHECK (role_type IN ('aqil', 'representative')),
    neighborhood_id UUID NOT NULL REFERENCES public.neighborhoods(id) ON DELETE CASCADE,
    square_id       UUID REFERENCES public.residential_squares(id) ON DELETE CASCADE,
    full_name       TEXT NOT NULL,
    phone           TEXT NOT NULL DEFAULT '',
    is_current      BOOLEAN NOT NULL DEFAULT TRUE,
    assigned_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    unassigned_at   TIMESTAMPTZ,
    assigned_by     UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    notes           TEXT NOT NULL DEFAULT '',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sync_version    BIGINT NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_representatives_profile ON public.representatives(profile_id);
CREATE INDEX IF NOT EXISTS idx_representatives_neighborhood ON public.representatives(neighborhood_id);
CREATE INDEX IF NOT EXISTS idx_representatives_square ON public.representatives(square_id);
CREATE INDEX IF NOT EXISTS idx_representatives_current ON public.representatives(is_current, role_type);

CREATE TRIGGER trg_representatives_updated_at
    BEFORE UPDATE ON public.representatives
    FOR EACH ROW EXECUTE FUNCTION public.handle_updated_at_and_version();

-- ============================================================================
-- 5. جدول المواطنين (people) - يدعم 4,000+ سجل بكفاءة بحث عالية
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.people (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    citizen_code        TEXT NOT NULL UNIQUE,
    full_name           TEXT NOT NULL,
    id_type             TEXT NOT NULL DEFAULT 'هوية وطنية'
                        CHECK (id_type IN (
                            'هوية وطنية',
                            'جواز سفر',
                            'استبيان',
                            'بطاقة عائلية',
                            'بطاقة رقم جلوس',
                            'بطاقة تعريفية'
                        )),
    national_id         TEXT NOT NULL,
    id_issue_date       DATE,
    phone               TEXT NOT NULL DEFAULT '',
    governorate         TEXT NOT NULL DEFAULT '',
    district            TEXT NOT NULL DEFAULT '',
    neighborhood_id     UUID NOT NULL REFERENCES public.neighborhoods(id) ON DELETE RESTRICT,
    square_id           UUID NOT NULL REFERENCES public.residential_squares(id) ON DELETE RESTRICT,
    occupation          TEXT NOT NULL DEFAULT '',
    family_size         INTEGER NOT NULL DEFAULT 1 CHECK (family_size >= 1),
    housing_type        TEXT NOT NULL DEFAULT 'مستأجر'
                        CHECK (housing_type IN ('مالك', 'مستأجر', 'أخرى')),
    housing_status      TEXT NOT NULL DEFAULT 'مستقر',
    displacement_date   DATE,
    health_status       TEXT NOT NULL DEFAULT 'سليم',
    health_details      TEXT NOT NULL DEFAULT '',
    last_aid_date       DATE,
    notes               TEXT NOT NULL DEFAULT '',
    photo_storage_path  TEXT,
    created_by          UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    created_by_name     TEXT NOT NULL DEFAULT '',
    updated_by          UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    is_deleted          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sync_version        BIGINT NOT NULL DEFAULT 1
);

-- منع التكرار غير المقصود في رقم الهوية مع نوع الهوية للسجلات النشطة
CREATE UNIQUE INDEX IF NOT EXISTS uq_people_active_national_id
    ON public.people(id_type, national_id)
    WHERE is_deleted = FALSE AND national_id <> '';

CREATE INDEX IF NOT EXISTS idx_people_full_name_trgm ON public.people USING GIN (full_name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_people_national_id ON public.people(national_id);
CREATE INDEX IF NOT EXISTS idx_people_phone ON public.people(phone);
CREATE INDEX IF NOT EXISTS idx_people_neighborhood_square ON public.people(neighborhood_id, square_id);
CREATE INDEX IF NOT EXISTS idx_people_health_status ON public.people(health_status);
CREATE INDEX IF NOT EXISTS idx_people_housing_type ON public.people(housing_type);
CREATE INDEX IF NOT EXISTS idx_people_created_at ON public.people(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_people_created_by ON public.people(created_by);

CREATE TRIGGER trg_people_updated_at
    BEFORE UPDATE ON public.people
    FOR EACH ROW EXECUTE FUNCTION public.handle_updated_at_and_version();

-- ============================================================================
-- 6. جدول وثائق وصور الهويات (identity_documents)
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.identity_documents (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    person_id           UUID NOT NULL REFERENCES public.people(id) ON DELETE CASCADE,
    document_type       TEXT NOT NULL
                        CHECK (document_type IN (
                            'هوية وطنية',
                            'جواز سفر',
                            'استبيان',
                            'بطاقة عائلية',
                            'بطاقة رقم جلوس',
                            'بطاقة تعريفية'
                        )),
    side                TEXT NOT NULL DEFAULT 'single'
                        CHECK (side IN ('front', 'back', 'single')),
    storage_path        TEXT NOT NULL,
    mime_type           TEXT NOT NULL DEFAULT 'image/jpeg',
    file_size_bytes     BIGINT NOT NULL DEFAULT 0,
    sha256_checksum     TEXT,
    created_by          UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    is_deleted          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sync_version        BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT uq_person_doc_side UNIQUE (person_id, document_type, side)
);

CREATE INDEX IF NOT EXISTS idx_identity_docs_person ON public.identity_documents(person_id);

CREATE TRIGGER trg_identity_docs_updated_at
    BEFORE UPDATE ON public.identity_documents
    FOR EACH ROW EXECUTE FUNCTION public.handle_updated_at_and_version();

-- ============================================================================
-- 7. جدول برامج ومشاريع المساعدات (aid_programs)
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.aid_programs (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                    TEXT NOT NULL,
    aid_type                TEXT NOT NULL,
    donor_name              TEXT NOT NULL DEFAULT '',
    distribution_date       DATE NOT NULL,
    target_neighborhood_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    target_square_ids       JSONB NOT NULL DEFAULT '[]'::jsonb,
    unit_label              TEXT NOT NULL DEFAULT 'سلة',
    default_quantity        NUMERIC(12, 2) NOT NULL DEFAULT 1,
    default_amount          NUMERIC(12, 2) NOT NULL DEFAULT 0,
    status                  TEXT NOT NULL DEFAULT 'active'
                            CHECK (status IN ('planned', 'active', 'completed', 'cancelled')),
    notes                   TEXT NOT NULL DEFAULT '',
    created_by              UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    created_by_name         TEXT NOT NULL DEFAULT '',
    is_deleted              BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sync_version            BIGINT NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_aid_programs_date ON public.aid_programs(distribution_date DESC);
CREATE INDEX IF NOT EXISTS idx_aid_programs_status ON public.aid_programs(status);

CREATE TRIGGER trg_aid_programs_updated_at
    BEFORE UPDATE ON public.aid_programs
    FOR EACH ROW EXECUTE FUNCTION public.handle_updated_at_and_version();

-- ============================================================================
-- 8. جدول المستفيدين المعتمدين في كل مساعدة (aid_beneficiaries)
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.aid_beneficiaries (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id          UUID NOT NULL REFERENCES public.aid_programs(id) ON DELETE CASCADE,
    person_id           UUID NOT NULL REFERENCES public.people(id) ON DELETE CASCADE,
    allocated_quantity  NUMERIC(12, 2) NOT NULL DEFAULT 1,
    allocated_amount    NUMERIC(12, 2) NOT NULL DEFAULT 0,
    is_approved         BOOLEAN NOT NULL DEFAULT TRUE,
    notes               TEXT NOT NULL DEFAULT '',
    created_by          UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sync_version        BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT uq_aid_beneficiary_unique UNIQUE (program_id, person_id)
);

CREATE INDEX IF NOT EXISTS idx_aid_beneficiaries_program ON public.aid_beneficiaries(program_id);
CREATE INDEX IF NOT EXISTS idx_aid_beneficiaries_person ON public.aid_beneficiaries(person_id);

CREATE TRIGGER trg_aid_beneficiaries_updated_at
    BEFORE UPDATE ON public.aid_beneficiaries
    FOR EACH ROW EXECUTE FUNCTION public.handle_updated_at_and_version();

-- ============================================================================
-- 9. جدول توزيع واستلام المساعدات (aid_distributions)
-- قيد فريد (program_id, person_id) لمنع التكرار غير المقصود في الاستلام
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.aid_distributions (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id                  UUID NOT NULL REFERENCES public.aid_programs(id) ON DELETE CASCADE,
    beneficiary_id              UUID REFERENCES public.aid_beneficiaries(id) ON DELETE SET NULL,
    person_id                   UUID NOT NULL REFERENCES public.people(id) ON DELETE CASCADE,
    quantity_delivered          NUMERIC(12, 2) NOT NULL DEFAULT 1,
    amount_delivered            NUMERIC(12, 2) NOT NULL DEFAULT 0,
    received_status             TEXT NOT NULL DEFAULT 'received'
                                CHECK (received_status IN ('pending', 'received', 'absent', 'rejected')),
    received_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    responsible_employee_id     UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    responsible_employee_name   TEXT NOT NULL DEFAULT '',
    notes                       TEXT NOT NULL DEFAULT '',
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sync_version                BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT uq_aid_distribution_no_duplicate UNIQUE (program_id, person_id)
);

CREATE INDEX IF NOT EXISTS idx_aid_distributions_program ON public.aid_distributions(program_id);
CREATE INDEX IF NOT EXISTS idx_aid_distributions_person ON public.aid_distributions(person_id);
CREATE INDEX IF NOT EXISTS idx_aid_distributions_received_at ON public.aid_distributions(received_at DESC);

CREATE TRIGGER trg_aid_distributions_updated_at
    BEFORE UPDATE ON public.aid_distributions
    FOR EACH ROW EXECUTE FUNCTION public.handle_updated_at_and_version();

-- ============================================================================
-- 10. جدول إعدادات التطبيق والترويسة الرسمية (app_settings)
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.app_settings (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    setting_key                 TEXT NOT NULL UNIQUE DEFAULT 'global',
    organization_title          TEXT NOT NULL DEFAULT 'سجل الفهد الميداني',
    header_subtitle             TEXT NOT NULL DEFAULT 'سجل حصر المواطنين والمساعدات الإنسانية',
    header_right_text           TEXT NOT NULL DEFAULT E'الجمهورية اليمنية\nمحافظة عدن - مديرية المنصورة\nسجل الفهد الميداني',
    header_left_text            TEXT NOT NULL DEFAULT E'نظام سجل الفهد\nإدارة السكان والمساعدات\nالوثائق الرسمية',
    header_font_family          TEXT NOT NULL DEFAULT 'Cairo',
    header_font_size_sp         NUMERIC(5, 2) NOT NULL DEFAULT 13.0,
    official_header_path        TEXT,
    eagle_emblem_path           TEXT,
    default_governorate         TEXT NOT NULL DEFAULT 'عدن',
    default_district            TEXT NOT NULL DEFAULT 'المنصورة',
    default_paper_size          TEXT NOT NULL DEFAULT 'A4',
    default_orientation         TEXT NOT NULL DEFAULT 'portrait'
                                CHECK (default_orientation IN ('portrait', 'landscape')),
    updated_by                  UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sync_version                BIGINT NOT NULL DEFAULT 1
);

CREATE TRIGGER trg_app_settings_updated_at
    BEFORE UPDATE ON public.app_settings
    FOR EACH ROW EXECUTE FUNCTION public.handle_updated_at_and_version();

-- ============================================================================
-- 11. جدول سجل العمليات والتدقيق (audit_logs)
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.audit_logs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_profile_id    UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    actor_username      TEXT NOT NULL,
    action_type         TEXT NOT NULL
                        CHECK (action_type IN (
                            'INSERT', 'UPDATE', 'DELETE', 'LOGIN',
                            'LOGOUT', 'BACKUP', 'RESTORE', 'EXPORT', 'SYNC'
                        )),
    table_name          TEXT NOT NULL,
    record_id           TEXT NOT NULL DEFAULT '',
    summary_ar          TEXT NOT NULL,
    old_data            JSONB,
    new_data            JSONB,
    device_id           TEXT NOT NULL DEFAULT '',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_logs_actor ON public.audit_logs(actor_profile_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_table_record ON public.audit_logs(table_name, record_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at ON public.audit_logs(created_at DESC);

-- ============================================================================
-- 12. جدول بيانات المزامنة وحل التعارضات (sync_metadata)
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.sync_metadata (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id           TEXT NOT NULL,
    entity_table        TEXT NOT NULL,
    record_id           UUID NOT NULL,
    sync_status         TEXT NOT NULL DEFAULT 'Pending'
                        CHECK (sync_status IN ('Pending', 'Syncing', 'Synced', 'Failed', 'Conflict')),
    retry_count         INTEGER NOT NULL DEFAULT 0,
    last_error          TEXT,
    local_updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    server_synced_at    TIMESTAMPTZ,
    conflict_payload    JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_sync_device_table_record UNIQUE (device_id, entity_table, record_id)
);

CREATE INDEX IF NOT EXISTS idx_sync_metadata_status ON public.sync_metadata(sync_status);
CREATE INDEX IF NOT EXISTS idx_sync_metadata_table ON public.sync_metadata(entity_table, record_id);
