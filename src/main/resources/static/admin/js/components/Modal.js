const Modal = {
    name: 'Modal',
    props: {
        title: {
            type: String,
            default: ''
        }
    },
    emits: ['close'],
    methods: {
        close() {
            this.$emit('close');
        },
        handleBackdropClick(event) {
            if (event.target === event.currentTarget) {
                this.close();
            }
        }
    },
    template: `
        <div class="modal" @click="handleBackdropClick">
            <div class="modal-content" @click.stop>
                <div class="modal-header">
                    <h2>{{ title }}</h2>
                    <button class="close" @click="close" type="button">
                        <i data-lucide="x" class="w-5 h-5"></i>
                    </button>
                </div>
                <div class="modal-body">
                    <slot></slot>
                </div>
            </div>
        </div>
    `,
    updated() {
        if (window.lucide) {
            window.lucide.createIcons();
        }
    }
};

window.Modal = Modal;
export default Modal;

