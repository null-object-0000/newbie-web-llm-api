const Header = {
    name: 'Header',
    data() {
        return {
            theme: 'dark'
        };
    },
    mounted() {
        this.loadTheme();
    },
    methods: {
        loadTheme() {
            const savedTheme = localStorage.getItem('app-theme-preference');
            const systemDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
            this.theme = savedTheme === 'light' ? 'light' : (savedTheme === 'dark' ? 'dark' : (systemDark ? 'dark' : 'light'));
        },
        toggleTheme() {
            this.theme = this.theme === 'light' ? 'dark' : 'light';
            localStorage.setItem('app-theme-preference', this.theme);
            
            if (this.theme === 'dark') {
                document.documentElement.classList.add('dark');
                document.documentElement.style.backgroundColor = '#1d232a';
            } else {
                document.documentElement.classList.remove('dark');
                document.documentElement.style.backgroundColor = '#FAFBFC';
            }
        }
    },
    template: `
        <div class="header">
            <div class="flex items-center justify-between">
                <div>
                    <h1>账号管理后台</h1>
                    <p>管理账号和 API 密钥</p>
                </div>
                <button 
                    @click="toggleTheme"
                    class="w-10 h-10 rounded-full bg-gray-100 dark:bg-gray-700 hover:bg-gray-200 dark:hover:bg-gray-600 flex items-center justify-center transition-colors"
                    :title="theme === 'light' ? '切换到深色模式' : '切换到浅色模式'"
                >
                    <i v-if="theme === 'light'" data-lucide="moon" class="w-5 h-5 text-gray-700 dark:text-gray-300"></i>
                    <i v-else data-lucide="sun" class="w-5 h-5 text-gray-700 dark:text-gray-300"></i>
                </button>
            </div>
        </div>
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
