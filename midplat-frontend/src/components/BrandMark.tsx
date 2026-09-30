export function BrandMark({ size = 22 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      data-brand-mark="neural-core"
      aria-hidden="true"
    >
      <path
        d="M12 3.5v3.2M20.5 12h-3.2M12 20.5v-3.2M3.5 12h3.2M5.8 5.8l2.25 2.25M18.2 5.8l-2.25 2.25M18.2 18.2l-2.25-2.25M5.8 18.2l2.25-2.25"
        stroke="currentColor"
        strokeWidth="1.45"
        strokeLinecap="round"
      />
      <path
        d="m12 7.2 4.8 4.8-4.8 4.8L7.2 12 12 7.2Z"
        fill="currentColor"
        fillOpacity=".18"
        stroke="currentColor"
        strokeWidth="1.55"
        strokeLinejoin="round"
      />
      <circle cx="12" cy="12" r="1.65" fill="currentColor" />
      <circle cx="12" cy="3.5" r="1.2" fill="currentColor" />
      <circle cx="20.5" cy="12" r="1.2" fill="currentColor" />
      <circle cx="12" cy="20.5" r="1.2" fill="currentColor" />
      <circle cx="3.5" cy="12" r="1.2" fill="currentColor" />
    </svg>
  );
}
