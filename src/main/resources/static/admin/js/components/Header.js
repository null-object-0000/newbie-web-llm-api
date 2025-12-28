const Header = {
    name: 'Header',
    template: `
        <nav class="header-nav">
            <div class="max-w-7xl mx-auto px-8 relative" style="z-index: 10;">
                <div class="flex items-center justify-between h-16">
                    <!-- Logo - 左侧 -->
                    <div class="flex items-center">
                        <div class="text-xl font-semibold text-gray-900 dark:text-base-content flex items-center gap-2">
                            账号管理后台
                        </div>
                    </div>
                </div>
            </div>
        </nav>
    `,
    updated() {
        // 重新初始化 Lucide 图标
        if (window.lucide) {
            window.lucide.createIcons();
        }
    }
};

window.Header = Header;
export default Header;
