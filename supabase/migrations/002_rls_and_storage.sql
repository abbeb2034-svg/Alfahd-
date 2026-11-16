-- ============================================================================
-- مشروع سجل الفهد - سياسات الحماية RLS وإعدادات التخزين الخاص (Storage)
-- الملف: 002_rls_and_storage.sql
-- ============================================================================

-- دوال مساعدة للتحقق من الدور الحالي للمستخدم المعتمد عبر Supabase Auth
CREATE OR REPLACE FUNCTION public.current_profile_id()
RETURNS UUID
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT id FROM public.profiles
    WHERE auth_user_id = auth.uid() AND is_active = TRUE
    LIMIT 1;
$$;

CREATE OR REPLACE FUNCTION public.current_user_role()
RETURNS TEXT
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT role FROM public.profiles
    WHERE auth_user_id = auth.uid() AND is_active = TRUE
    LIMIT 1;
$$;

CREATE OR REPLACE FUNCTION public.is_admin()
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT COALESCE(public.current_user_role() = 'admin', FALSE);
$$;

CREATE OR REPLACE FUNCTION public.is_staff()
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT COALESCE(public.current_user_role() IN ('admin', 'employee', 'aqil', 'representative'), FALSE);
$$;

-- تفعيل Row Level Security على جميع الجداول الـ 12
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.neighborhoods ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.residential_squares ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.representatives ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.people ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.identity_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.aid_programs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.aid_beneficiaries ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.aid_distributions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.app_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.audit_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sync_metadata ENABLE ROW LEVEL SECURITY;

-- 1. سياسات profiles
CREATE POLICY "profiles_select_staff"
    ON public.profiles FOR SELECT
    TO authenticated
    USING (public.is_admin() OR auth_user_id = auth.uid());

CREATE POLICY "profiles_admin_all"
    ON public.profiles FOR ALL
    TO authenticated
    USING (public.is_admin())
    WITH CHECK (public.is_admin());

-- 2. سياسات neighborhoods
CREATE POLICY "neighborhoods_read_staff"
    ON public.neighborhoods FOR SELECT
    TO authenticated
    USING (public.is_staff());

CREATE POLICY "neighborhoods_write_admin"
    ON public.neighborhoods FOR ALL
    TO authenticated
    USING (public.is_admin())
    WITH CHECK (public.is_admin());

-- 3. سياسات residential_squares
CREATE POLICY "squares_read_staff"
    ON public.residential_squares FOR SELECT
    TO authenticated
    USING (public.is_staff());

CREATE POLICY "squares_write_admin"
    ON public.residential_squares FOR ALL
    TO authenticated
    USING (public.is_admin())
    WITH CHECK (public.is_admin());

-- 4. سياسات representatives
CREATE POLICY "representatives_read_staff"
    ON public.representatives FOR SELECT
    TO authenticated
    USING (public.is_staff());

CREATE POLICY "representatives_write_admin"
    ON public.representatives FOR ALL
    TO authenticated
    USING (public.is_admin())
    WITH CHECK (public.is_admin());

-- 5. سياسات people (الموظف يقرأ ويضيف فقط؛ المدير يعدل ويحذف)
CREATE POLICY "people_read_staff"
    ON public.people FOR SELECT
    TO authenticated
    USING (public.is_staff());

CREATE POLICY "people_insert_staff"
    ON public.people FOR INSERT
    TO authenticated
    WITH CHECK (public.is_staff());

CREATE POLICY "people_update_admin_only"
    ON public.people FOR UPDATE
    TO authenticated
    USING (public.is_admin())
    WITH CHECK (public.is_admin());

CREATE POLICY "people_delete_admin_only"
    ON public.people FOR DELETE
    TO authenticated
    USING (public.is_admin());

-- 6. سياسات identity_documents (الموظف يضيف وثائق المواطن الجديد؛ المدير يعدل ويحذف)
CREATE POLICY "identity_docs_read_staff"
    ON public.identity_documents FOR SELECT
    TO authenticated
    USING (public.is_staff());

CREATE POLICY "identity_docs_insert_staff"
    ON public.identity_documents FOR INSERT
    TO authenticated
    WITH CHECK (public.is_staff());

CREATE POLICY "identity_docs_update_delete_admin"
    ON public.identity_documents FOR UPDATE
    TO authenticated
    USING (public.is_admin())
    WITH CHECK (public.is_admin());

CREATE POLICY "identity_docs_delete_admin"
    ON public.identity_documents FOR DELETE
    TO authenticated
    USING (public.is_admin());

-- 7-9. سياسات المساعدات (aid_programs, aid_beneficiaries, aid_distributions)
CREATE POLICY "aid_programs_read_staff"
    ON public.aid_programs FOR SELECT
    TO authenticated
    USING (public.is_staff());

CREATE POLICY "aid_programs_admin_write"
    ON public.aid_programs FOR ALL
    TO authenticated
    USING (public.is_admin())
    WITH CHECK (public.is_admin());

CREATE POLICY "aid_beneficiaries_read_staff"
    ON public.aid_beneficiaries FOR SELECT
    TO authenticated
    USING (public.is_staff());

CREATE POLICY "aid_beneficiaries_admin_write"
    ON public.aid_beneficiaries FOR ALL
    TO authenticated
    USING (public.is_admin())
    WITH CHECK (public.is_admin());

CREATE POLICY "aid_distributions_read_staff"
    ON public.aid_distributions FOR SELECT
    TO authenticated
    USING (public.is_staff());

CREATE POLICY "aid_distributions_insert_staff"
    ON public.aid_distributions FOR INSERT
    TO authenticated
    WITH CHECK (public.is_staff());

CREATE POLICY "aid_distributions_update_delete_admin"
    ON public.aid_distributions FOR UPDATE
    TO authenticated
    USING (public.is_admin())
    WITH CHECK (public.is_admin());

CREATE POLICY "aid_distributions_delete_admin"
    ON public.aid_distributions FOR DELETE
    TO authenticated
    USING (public.is_admin());

-- 10. سياسات app_settings
CREATE POLICY "app_settings_read_staff"
    ON public.app_settings FOR SELECT
    TO authenticated
    USING (public.is_staff());

CREATE POLICY "app_settings_write_admin"
    ON public.app_settings FOR ALL
    TO authenticated
    USING (public.is_admin())
    WITH CHECK (public.is_admin());

-- 11. سياسات audit_logs (إدخال فقط للمصرح لهم، قراءة للمدير، منع التعديل والحذف)
CREATE POLICY "audit_logs_read_admin"
    ON public.audit_logs FOR SELECT
    TO authenticated
    USING (public.is_admin());

CREATE POLICY "audit_logs_insert_staff"
    ON public.audit_logs FOR INSERT
    TO authenticated
    WITH CHECK (public.is_staff());

-- 12. سياسات sync_metadata
CREATE POLICY "sync_metadata_staff"
    ON public.sync_metadata FOR ALL
    TO authenticated
    USING (public.is_staff())
    WITH CHECK (public.is_staff());

-- ============================================================================
-- إعداد حاوية تخزين صور الهويات الخاصة (Private Storage Bucket)
-- ============================================================================
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
    'identity-documents',
    'identity-documents',
    FALSE,
    5242880, -- 5 MB
    ARRAY['image/jpeg', 'image/png', 'image/webp']
)
ON CONFLICT (id) DO UPDATE SET public = FALSE;

INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
    'official-branding',
    'official-branding',
    FALSE,
    5242880,
    ARRAY['image/jpeg', 'image/png', 'image/webp']
)
ON CONFLICT (id) DO UPDATE SET public = FALSE;

CREATE POLICY "storage_identity_docs_read_staff"
    ON storage.objects FOR SELECT
    TO authenticated
    USING (bucket_id = 'identity-documents' AND public.is_staff());

CREATE POLICY "storage_identity_docs_insert_staff"
    ON storage.objects FOR INSERT
    TO authenticated
    WITH CHECK (bucket_id = 'identity-documents' AND public.is_staff());

CREATE POLICY "storage_identity_docs_delete_admin"
    ON storage.objects FOR DELETE
    TO authenticated
    USING (bucket_id = 'identity-documents' AND public.is_admin());

CREATE POLICY "storage_branding_read_staff"
    ON storage.objects FOR SELECT
    TO authenticated
    USING (bucket_id = 'official-branding' AND public.is_staff());

CREATE POLICY "storage_branding_admin_write"
    ON storage.objects FOR ALL
    TO authenticated
    USING (bucket_id = 'official-branding' AND public.is_admin())
    WITH CHECK (bucket_id = 'official-branding' AND public.is_admin());
