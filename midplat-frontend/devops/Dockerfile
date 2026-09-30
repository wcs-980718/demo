FROM nginx:alpine

RUN rm -f /etc/nginx/conf.d/default.conf
ENV MIDPLAT_BACKEND_HOST=midplat-backend
ENV MIDPLAT_BACKEND_PORT=8090
COPY nginx.web.conf /etc/nginx/templates/default.conf.template
COPY dist /static

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
